#!/usr/bin/env python3
"""
Video verification script for WebRTC storage canary.

Compares the GetClip MP4 (received from storage) against the source H.264
frames sent by the C master to determine ConsumerStorageAvailability.

Pipeline:
  1. Build a reference video from the raw H.264 sample frames
  2. Compare durations (reference vs GetClip)
  3. Extract 1 frame per second from both videos
  4. Compare matching seconds via SSIM
  5. Emit storage_availability: 1 if score > threshold, 0 otherwise

Score = (duration_ratio) * (ssim_pass_rate)
  - duration_ratio = min(clip_duration, expected_duration) / expected_duration
  - ssim_pass_rate  = frames_passing_ssim / frames_compared

Usage:
  python verify.py --recording clip.mp4 --source-frames ../../assets/h264SampleFrames --json
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

import pytesseract
import numpy as np
from PIL import Image
from skimage.metrics import structural_similarity as ssim


FPS = 30
TOTAL_SOURCE_FRAMES = 4676
EXPECTED_DURATION = TOTAL_SOURCE_FRAMES / FPS  # ~155.87s

# Timer crop coordinates for 1280x720 frames
TIMER_CROP = (25, 20, 145, 90)


def ocr_timer(frame_path):
    """Read the timer text from a frame image."""
    img = Image.open(frame_path)
    timer_region = img.crop(TIMER_CROP)
    text = pytesseract.image_to_string(timer_region, config='--psm 7').strip()
    return text


def parse_timer(timer_text):
    """Parse timer text like '0:00:17.080' into total seconds (float)."""
    import re
    timer_text = timer_text.replace(',', '.').replace('..', '.')

    match = re.match(r'(\d+):(\d+):(\d+)\.(\d+)', timer_text)
    if match:
        h, m, s, ms = match.groups()
        return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000.0

    match = re.match(r'(\d+):(\d+)\.(\d+)', timer_text)
    if match:
        m, s, ms = match.groups()
        return int(m) * 60 + int(s) + int(ms) / 1000.0

    match = re.match(r'(\d+)\.(\d+)', timer_text)
    if match:
        s, ms = match.groups()
        return int(s) + int(ms) / 1000.0

    return None


def detect_clip_offset(clip_frames, num_attempts=5):
    """OCR the first few frames of the GetClip to determine the time offset in seconds.
    Returns the offset as an integer number of seconds, or 0 if OCR fails."""
    for i in range(min(num_attempts, len(clip_frames))):
        timer_text = ocr_timer(clip_frames[i])
        timer_sec = parse_timer(timer_text)
        if timer_sec is not None:
            # The extracted frame index i corresponds to second i of the clip.
            # The timer tells us what second of the source stream this is.
            offset = int(timer_sec) - i
            print(f"  OCR frame {i}: timer='{timer_text}' -> {timer_sec:.1f}s, offset={offset}s")
            return max(0, offset)
        else:
            print(f"  OCR frame {i}: failed ('{timer_text}'), trying next")
    print("  WARNING: Could not determine offset via OCR, using 0")
    return 0


def get_video_duration(video_path):
    """Get video duration in seconds using ffprobe."""
    cmd = [
        'ffprobe', '-v', 'error',
        '-show_entries', 'format=duration',
        '-of', 'default=noprint_wrappers=1:nokey=1',
        video_path
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0 or result.stdout.strip() == 'N/A':
        remuxed = video_path + '.remuxed.mkv'
        subprocess.run(['ffmpeg', '-y', '-i', video_path, '-c', 'copy', remuxed],
                       capture_output=True, text=True)
        result = subprocess.run(
            ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
             '-of', 'default=noprint_wrappers=1:nokey=1', remuxed],
            capture_output=True, text=True)
        os.remove(remuxed)
    try:
        return float(result.stdout.strip())
    except ValueError:
        return None


def build_reference_video(source_dir, output_path):
    """Build a reference MP4 from the raw H.264 sample frames."""
    print("Building reference video from source frames...")
    stream_path = output_path + '.h264'
    with open(stream_path, 'wb') as out:
        for i in range(1, TOTAL_SOURCE_FRAMES + 1):
            h264_path = os.path.join(source_dir, f'frame-{i:04d}.h264')
            if os.path.exists(h264_path):
                with open(h264_path, 'rb') as f:
                    out.write(f.read())

    cmd = [
        'ffmpeg', '-y', '-f', 'h264', '-r', str(FPS),
        '-i', stream_path, '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
        output_path
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    os.remove(stream_path)
    if result.returncode != 0:
        print(f"ffmpeg error building reference: {result.stderr}", file=sys.stderr)
        return None
    print(f"Reference video built: {output_path}")
    return output_path


def extract_frames_1fps(video_path, output_dir):
    """Extract 1 frame per second from a video."""
    os.makedirs(output_dir, exist_ok=True)
    cmd = [
        'ffmpeg', '-i', video_path, '-vf', 'fps=1',
        os.path.join(output_dir, 'frame-%05d.png')
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ffmpeg error: {result.stderr}", file=sys.stderr)
        return []
    frames = sorted(glob.glob(os.path.join(output_dir, 'frame-*.png')))
    print(f"Extracted {len(frames)} frames from {video_path}")
    return frames


def compute_ssim(img_path_a, img_path_b):
    """Compute SSIM between two images. Resizes to match if needed."""
    img_a = np.array(Image.open(img_path_a).convert('L'))
    img_b = np.array(Image.open(img_path_b).convert('L'))
    if img_a.shape != img_b.shape:
        img_b = np.array(
            Image.open(img_path_b).convert('L').resize(
                (img_a.shape[1], img_a.shape[0]), Image.LANCZOS))
    return ssim(img_a, img_b)


def main():
    parser = argparse.ArgumentParser(
        description='Verify GetClip video against source frames for ConsumerStorageAvailability')
    parser.add_argument('--recording', required=True, help='Path to GetClip MP4')
    parser.add_argument('--source-frames', required=True, help='Path to H.264 source frames directory')
    parser.add_argument('--expected-duration', type=float, default=EXPECTED_DURATION,
                        help=f'Expected duration in seconds (default: {EXPECTED_DURATION})')
    parser.add_argument('--threshold', type=float, default=0.85,
                        help='Overall score threshold for availability=1 (default: 0.85)')
    parser.add_argument('--ssim-threshold', type=float, default=0.90,
                        help='Per-frame SSIM threshold (default: 0.90)')
    parser.add_argument('--keep-frames', action='store_true', help='Keep extracted frames')
    parser.add_argument('--verbose', action='store_true')
    parser.add_argument('--json', action='store_true', dest='json_output',
                        help='Output JSON to stdout')
    args = parser.parse_args()

    if args.json_output:
        import builtins, functools
        builtins.print = functools.partial(builtins.print, file=sys.stderr)

    if not os.path.exists(args.recording):
        print(f"Recording not found: {args.recording}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isdir(args.source_frames):
        print(f"Source frames directory not found: {args.source_frames}", file=sys.stderr)
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix='video-verify-')
    try:
        # Phase 1: Build reference video from source frames
        print("\n--- Phase 1: Building reference video ---")
        ref_video = build_reference_video(
            args.source_frames, os.path.join(work_dir, 'reference.mp4'))
        if not ref_video:
            print("Failed to build reference video", file=sys.stderr)
            sys.exit(1)

        # Phase 2: Duration comparison
        print("\n--- Phase 2: Duration comparison ---")
        ref_duration = get_video_duration(ref_video)
        clip_duration = get_video_duration(args.recording)
        expected = args.expected_duration

        print(f"Reference duration: {ref_duration:.2f}s")
        print(f"GetClip duration:   {clip_duration:.2f}s")
        print(f"Expected duration:  {expected:.2f}s")

        duration_ratio = min(clip_duration, expected) / expected if expected > 0 else 0
        print(f"Duration ratio:     {duration_ratio:.4f}")

        # Phase 3: Extract 1fps from both
        print("\n--- Phase 3: Extracting frames at 1 FPS ---")
        ref_frames = extract_frames_1fps(ref_video, os.path.join(work_dir, 'ref'))
        clip_frames = extract_frames_1fps(args.recording, os.path.join(work_dir, 'clip'))

        # Phase 4: Determine clip offset via OCR and compare with alignment
        print("\n--- Phase 4: Detecting clip offset via OCR ---")
        offset = detect_clip_offset(clip_frames)
        print(f"Clip starts at second {offset} of the source stream")

        # Compare: clip frame i <-> ref frame (i + offset)
        num_compare = 0
        scores = []
        failures = []
        print(f"\n--- Phase 5: Comparing frames (offset={offset}s) ---")
        for i in range(len(clip_frames)):
            ref_idx = i + offset
            if ref_idx >= len(ref_frames):
                break
            num_compare += 1
            score = compute_ssim(ref_frames[ref_idx], clip_frames[i])
            scores.append(score)
            passed = score >= args.ssim_threshold
            if not passed:
                failures.append((i + 1, ref_idx + 1, score))
            if i < 5 or not passed or args.verbose:
                print(f"  [clip sec {i+1} vs ref sec {ref_idx+1}] SSIM={score:.4f} {'PASS' if passed else 'FAIL'}")

        # Phase 6: Compute availability
        # Rule 1: duration ratio must be >= 0.85
        # Rule 2: no more than 5 frames with SSIM below 0.9
        print("\n--- Results ---")
        if not scores:
            print("No frames compared!", file=sys.stderr)
            result = {'storage_availability': 0, 'duration_ratio': 0,
                      'avg_ssim': 0, 'frames_compared': 0, 'frames_failed': 0}
        else:
            avg_ssim = sum(scores) / len(scores)
            low_ssim_frames = [(i, ref, s) for i, ref, s in failures]

            duration_ok = duration_ratio >= 0.85
            ssim_ok = len(low_ssim_frames) <= 5
            available = 1 if (duration_ok and ssim_ok) else 0

            print(f"Duration ratio:     {duration_ratio:.4f} ({'PASS' if duration_ok else 'FAIL'} — threshold: 0.85)")
            print(f"Low SSIM frames:    {len(low_ssim_frames)} ({'PASS' if ssim_ok else 'FAIL'} — max allowed: 5)")
            print(f"Average SSIM:       {avg_ssim:.4f}")
            print(f"Frames compared:    {num_compare}")
            print(f"Storage available:  {available}")

            if low_ssim_frames:
                print(f"\nFrames below 0.9 SSIM ({len(low_ssim_frames)}):")
                for clip_sec, ref_sec, score in low_ssim_frames:
                    print(f"  clip sec {clip_sec} vs ref sec {ref_sec}: SSIM={score:.4f}")

            result = {
                'storage_availability': available,
                'duration_ratio': round(duration_ratio, 4),
                'avg_ssim': round(avg_ssim, 4),
                'frames_compared': num_compare,
                'frames_failed': len(low_ssim_frames),
                'clip_offset_seconds': offset,
                'clip_duration': round(clip_duration, 2) if clip_duration else None,
                'ref_duration': round(ref_duration, 2) if ref_duration else None,
                'expected_duration': round(expected, 2),
            }

        if args.json_output:
            json.dump(result, sys.stdout)
            print()
        sys.exit(0 if result.get('storage_availability', 0) == 1 else 1)

    finally:
        if not args.keep_frames:
            shutil.rmtree(work_dir, ignore_errors=True)
        else:
            print(f"\nFrames kept at: {work_dir}")


if __name__ == '__main__':
    main()
