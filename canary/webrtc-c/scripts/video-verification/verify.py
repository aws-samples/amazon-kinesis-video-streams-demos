#!/usr/bin/env python3
"""
Video verification script for WebRTC storage canary.

Compares the received video (GetClip or viewer recording) against the source
H.264 frames to determine StorageAvailability.

Pipeline:
  1. Build a reference video from the raw H.264 sample frames
  2. Extract 1fps from the received clip
  3. OCR each clip frame to read the frame counter
  4. Extract only the needed reference frames (by frame number)
  5. Compare each clip frame against its matching reference frame via SSIM
  6. Emit storage_availability: 1 if thresholds pass, 0 otherwise

Thresholds (matching VideoVerificationComponent.java):
  - Duration >= 120 seconds
  - Max SSIM > 0.99
  - Avg SSIM > 0.85
  - Min SSIM > 0.03
  - Clip frame count >= TOTAL_SOURCE_FRAMES - 1500

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
DROPPED_FRAME_THRESHOLD = 1500

# Sync box crop coordinates for 1280x720 frames
TIMER_CROP = (25, 20, 145, 90)


def ocr_frame_number(frame_path):
    """Read the frame counter from the sync box region.
    Returns the frame number as an integer, or None if OCR fails."""
    img = Image.open(frame_path)
    timer_region = img.crop(TIMER_CROP)
    upscaled = timer_region.resize(
        (timer_region.width * 4, timer_region.height * 4), Image.LANCZOS)
    gray = upscaled.convert('L')
    threshold = gray.point(lambda x: 255 if x > 128 else 0)
    arr = np.array(threshold)
    white_rows = np.where(arr.mean(axis=1) > 128)[0]
    white_cols = np.where(arr.mean(axis=0) > 128)[0]
    if len(white_rows) > 10 and len(white_cols) > 10:
        tight = threshold.crop((white_cols[0]+5, white_rows[0]+5, white_cols[-1]-5, white_rows[-1]-5))
    else:
        tight = threshold
    text = pytesseract.image_to_string(
        tight, config='--psm 7 -c tessedit_char_whitelist=0123456789').strip()
    if text and text.isdigit():
        return int(text)
    return None


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


def extract_specific_frames(video_path, output_dir, frame_numbers):
    """Extract specific frames from a video by frame number (1-based).
    Uses a single ffmpeg call with a select filter for efficiency."""
    os.makedirs(output_dir, exist_ok=True)
    if not frame_numbers:
        return {}

    # Build select filter: select frames by 0-based index
    conditions = '+'.join(f'eq(n\\,{n-1})' for n in sorted(frame_numbers))
    select_filter = f"select='{conditions}'"

    cmd = [
        'ffmpeg', '-i', video_path,
        '-vf', select_filter,
        '-vsync', '0',
        os.path.join(output_dir, 'frame-%05d.png')
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ffmpeg error extracting frames: {result.stderr}", file=sys.stderr)
        return {}

    # Map output files back to frame numbers
    # ffmpeg outputs sequentially: frame-00001.png, frame-00002.png, ...
    # in the same order as the sorted frame_numbers
    output_files = sorted(glob.glob(os.path.join(output_dir, 'frame-*.png')))
    sorted_nums = sorted(frame_numbers)
    result_map = {}
    for idx, num in enumerate(sorted_nums):
        if idx < len(output_files):
            result_map[num] = output_files[idx]
    print(f"Extracted {len(result_map)} specific frames from {video_path}")
    return result_map


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
        description='Verify received video against source frames for StorageAvailability')
    parser.add_argument('--recording', required=True, help='Path to received video')
    parser.add_argument('--source-frames', required=True, help='Path to H.264 source frames directory')
    parser.add_argument('--expected-duration', type=float, default=EXPECTED_DURATION,
                        help=f'Expected duration in seconds (default: {EXPECTED_DURATION})')
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
        # Phase 1: Build reference video
        print("\n--- Phase 1: Building reference video ---")
        ref_video = build_reference_video(
            args.source_frames, os.path.join(work_dir, 'reference.mp4'))
        if not ref_video:
            print("Failed to build reference video", file=sys.stderr)
            sys.exit(1)

        # Phase 2: Duration and frame count check
        print("\n--- Phase 2: Duration and frame count ---")
        clip_duration = get_video_duration(args.recording)
        expected = args.expected_duration

        # Get total frame count of the clip
        fc_cmd = [
            'ffprobe', '-v', 'error', '-select_streams', 'v:0',
            '-count_frames', '-show_entries', 'stream=nb_read_frames',
            '-of', 'default=noprint_wrappers=1:nokey=1', args.recording
        ]
        fc_result = subprocess.run(fc_cmd, capture_output=True, text=True)
        try:
            clip_total_frames = int(fc_result.stdout.strip())
        except ValueError:
            clip_total_frames = 0

        print(f"Clip duration:      {clip_duration:.2f}s")
        print(f"Clip total frames:  {clip_total_frames}")
        print(f"Expected duration:  {expected:.2f}s")

        # Phase 3: Extract 1fps from clip
        print("\n--- Phase 3: Extracting clip frames at 1 FPS ---")
        clip_frames = extract_frames_1fps(args.recording, os.path.join(work_dir, 'clip'))
        if not clip_frames:
            print("Failed to extract clip frames", file=sys.stderr)
            sys.exit(1)

        # Phase 4: OCR all clip frames to get frame numbers
        print(f"\n--- Phase 4: OCR {len(clip_frames)} clip frames ---")
        clip_to_ref = []  # list of (clip_frame_path, ref_frame_number)
        ocr_failures = 0
        for i, clip_frame in enumerate(clip_frames):
            frame_num = ocr_frame_number(clip_frame)
            if frame_num is None or frame_num < 1 or frame_num > TOTAL_SOURCE_FRAMES:
                if i < 5 or args.verbose:
                    print(f"  [clip sec {i+1}] OCR: {frame_num} — skipping")
                ocr_failures += 1
                continue
            clip_to_ref.append((clip_frame, frame_num))
            if i < 5 or args.verbose:
                print(f"  [clip sec {i+1}] frame #{frame_num}")

        print(f"OCR complete: {len(clip_to_ref)} matched, {ocr_failures} failed")

        # Phase 5: Extract only the needed reference frames
        needed_frames = set(num for _, num in clip_to_ref)
        print(f"\n--- Phase 5: Extracting {len(needed_frames)} reference frames ---")
        ref_frame_map = extract_specific_frames(
            ref_video, os.path.join(work_dir, 'ref'), needed_frames)

        # Phase 6: SSIM comparison
        print(f"\n--- Phase 6: SSIM comparison ---")
        scores = []
        for i, (clip_frame, ref_num) in enumerate(clip_to_ref):
            ref_path = ref_frame_map.get(ref_num)
            if not ref_path:
                continue
            score = compute_ssim(clip_frame, ref_path)
            scores.append(score)
            clip_sec = clip_frames.index(clip_frame) + 1
            if clip_sec <= 5 or score < 0.9 or args.verbose:
                print(f"  [clip sec {clip_sec}] frame #{ref_num} -> SSIM={score:.4f}")

        # Phase 7: Compute availability
        print("\n--- Results ---")
        if not scores:
            print("No frames compared!", file=sys.stderr)
            result = {'storage_availability': 0, 'clip_duration': clip_duration,
                      'frames_compared': 0, 'ocr_failures': ocr_failures}
        else:
            avg_ssim = sum(scores) / len(scores)
            max_ssim = max(scores)
            min_ssim = min(scores)

            duration_ok = clip_duration is not None and clip_duration >= 120
            max_ssim_ok = max_ssim > 0.99
            avg_ssim_ok = avg_ssim > 0.85
            min_ssim_ok = min_ssim > 0.03
            frames_ok = clip_total_frames >= (TOTAL_SOURCE_FRAMES - DROPPED_FRAME_THRESHOLD)
            available = 1 if (duration_ok and max_ssim_ok and avg_ssim_ok and min_ssim_ok and frames_ok) else 0

            print(f"Duration:           {clip_duration:.2f}s ({'PASS' if duration_ok else 'FAIL'} — threshold: >= 120s)")
            print(f"Max SSIM:           {max_ssim:.4f} ({'PASS' if max_ssim_ok else 'FAIL'} — threshold: > 0.99)")
            print(f"Avg SSIM:           {avg_ssim:.4f} ({'PASS' if avg_ssim_ok else 'FAIL'} — threshold: > 0.85)")
            print(f"Min SSIM:           {min_ssim:.4f} ({'PASS' if min_ssim_ok else 'FAIL'} — threshold: > 0.03)")
            print(f"Clip frames:        {clip_total_frames} ({'PASS' if frames_ok else 'FAIL'} — threshold: >= {TOTAL_SOURCE_FRAMES - DROPPED_FRAME_THRESHOLD})")
            print(f"SSIM comparisons:   {len(scores)}")
            print(f"OCR failures:       {ocr_failures}")
            print(f"Storage available:  {available}")

            result = {
                'storage_availability': available,
                'max_ssim': round(max_ssim, 4),
                'avg_ssim': round(avg_ssim, 4),
                'min_ssim': round(min_ssim, 4),
                'frames_compared': len(scores),
                'ocr_failures': ocr_failures,
                'clip_duration': round(clip_duration, 2) if clip_duration else None,
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
