#!/usr/bin/env python3
"""
Video verification script for WebRTC canary.

Compares the viewer's recorded video against the source H.264 frames
sent by the C master to verify visual quality.

Pipeline:
  1. Extract frames from the viewer recording (ffmpeg)
  2. OCR the timer overlay from the first received frame (Tesseract)
  3. Compute the source frame offset from the timer value
  4. Decode matching source H.264 frames (ffmpeg)
  5. Compare frame pairs using SSIM

Usage:
  python verify.py --recording ../recordings/viewer-xxx.mp4 --source-frames ../../assets/h264SampleFrames
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

import pytesseract
from PIL import Image
from skimage.metrics import structural_similarity as ssim
import numpy as np


# Timer crop coordinates for 1280x720 frames
TIMER_CROP = (30, 30, 420, 80)

# Source video properties
FPS = 25
FRAME_DURATION_MS = 1000 / FPS  # 40ms
TOTAL_SOURCE_FRAMES = 1500


def extract_frames(video_path, output_dir, max_frames=None):
    """Extract frames from a video file using ffmpeg. Extracts 1 frame per second."""
    os.makedirs(output_dir, exist_ok=True)
    cmd = ['ffmpeg', '-i', video_path, '-vf', 'fps=1']
    if max_frames:
        cmd += ['-frames:v', str(max_frames)]
    cmd.append(os.path.join(output_dir, 'frame-%05d.png'))

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ffmpeg error: {result.stderr}", file=sys.stderr)
        sys.exit(1)

    frames = sorted(glob.glob(os.path.join(output_dir, 'frame-*.png')))
    print(f"Extracted {len(frames)} frames from {video_path}")
    return frames


def ocr_timer(frame_path):
    """Read the timer text from a frame image."""
    img = Image.open(frame_path)
    timer_region = img.crop(TIMER_CROP)
    text = pytesseract.image_to_string(timer_region, config='--psm 7').strip()
    return text


def parse_timer(timer_text):
    """Parse timer text like '0:00:17.080' into milliseconds."""
    # Handle formats: H:MM:SS.mmm or MM:SS.mmm or SS.mmm
    match = re.match(r'(\d+):(\d+):(\d+)\.(\d+)', timer_text)
    if match:
        h, m, s, ms = match.groups()
        return int(h) * 3600000 + int(m) * 60000 + int(s) * 1000 + int(ms)

    match = re.match(r'(\d+):(\d+)\.(\d+)', timer_text)
    if match:
        m, s, ms = match.groups()
        return int(m) * 60000 + int(s) * 1000 + int(ms)

    match = re.match(r'(\d+)\.(\d+)', timer_text)
    if match:
        s, ms = match.groups()
        return int(s) * 1000 + int(ms)

    return None


def timer_to_frame_index(timer_ms):
    """Convert a timer value in ms to a 1-based source frame index (1-1500)."""
    frame_index = int(timer_ms / FRAME_DURATION_MS)
    # Wrap around since source loops
    return (frame_index % TOTAL_SOURCE_FRAMES) + 1


def build_source_stream(source_dir, output_path):
    """Concatenate all H.264 frame files into a single decodable stream."""
    print("Building concatenated source stream...")
    with open(output_path, 'wb') as out:
        for i in range(1, TOTAL_SOURCE_FRAMES + 1):
            h264_path = os.path.join(source_dir, f'frame-{i:04d}.h264')
            if os.path.exists(h264_path):
                with open(h264_path, 'rb') as f:
                    out.write(f.read())
    print(f"Source stream built: {output_path}")


def extract_source_frames(source_stream, output_dir, frame_indices):
    """Extract specific frames from the concatenated source stream."""
    os.makedirs(output_dir, exist_ok=True)
    results = {}
    for idx in frame_indices:
        output_path = os.path.join(output_dir, f'source-{idx:04d}.png')
        cmd = [
            'ffmpeg', '-y', '-i', source_stream,
            '-vf', f'select=eq(n\\,{idx - 1})',
            '-frames:v', '1', '-vsync', '0',
            output_path
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode == 0:
            results[idx] = output_path
    return results


def compute_ssim(img_path_a, img_path_b):
    """Compute SSIM between two images. Resizes to match if needed."""
    img_a = np.array(Image.open(img_path_a).convert('L'))
    img_b = np.array(Image.open(img_path_b).convert('L'))

    # Resize img_b to match img_a if dimensions differ
    if img_a.shape != img_b.shape:
        img_b_pil = Image.open(img_path_b).convert('L').resize(
            (img_a.shape[1], img_a.shape[0]), Image.LANCZOS
        )
        img_b = np.array(img_b_pil)

    return ssim(img_a, img_b)


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
        # Duration not in header — remux to calculate it
        remuxed = video_path + '.remuxed.mkv'
        subprocess.run(['ffmpeg', '-y', '-i', video_path, '-c', 'copy', remuxed],
                       capture_output=True, text=True)
        result = subprocess.run(cmd[:6] + [remuxed], capture_output=True, text=True)
        os.remove(remuxed)

    try:
        return float(result.stdout.strip())
    except ValueError:
        return None


def get_total_frame_count(video_path):
    """Get total number of video frames using ffprobe."""
    cmd = [
        'ffprobe', '-v', 'error',
        '-select_streams', 'v:0',
        '-count_frames',
        '-show_entries', 'stream=nb_read_frames',
        '-of', 'default=noprint_wrappers=1:nokey=1',
        video_path
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def validate_frame_count(video_path, tolerance=0.1):
    """Validate that the frame count matches the expected count based on duration and FPS.
    Returns (passed, actual_frames, expected_frames, duration)."""
    duration = get_video_duration(video_path)
    if duration is None:
        return None, None, None, None

    actual_frames = get_total_frame_count(video_path)
    if actual_frames is None:
        return None, None, None, duration

    expected_frames = int(duration * FPS)
    diff_ratio = abs(actual_frames - expected_frames) / expected_frames if expected_frames > 0 else 0
    passed = diff_ratio <= tolerance

    return passed, actual_frames, expected_frames, duration


def main():
    parser = argparse.ArgumentParser(description='Verify viewer video against source frames')
    parser.add_argument('--recording', required=True, help='Path to viewer recording (mp4/webm)')
    parser.add_argument('--source-frames', required=True, help='Path to H.264 source frames directory')
    parser.add_argument('--max-compare', type=int, default=0, help='Max frames to compare (default: 0 = all)')
    parser.add_argument('--threshold', type=float, default=0.5, help='Min SSIM to pass (default: 0.5)')
    parser.add_argument('--expected-duration', type=float, default=0,
                        help='Expected video duration in seconds (e.g. canary run time). '
                             'When provided, frame loss is calculated against this instead of '
                             'the clip\'s self-reported duration.')
    parser.add_argument('--keep-frames', action='store_true', help='Keep extracted frames after verification')
    parser.add_argument('--verbose', action='store_true', help='Print SSIM score for every frame')
    parser.add_argument('--json', action='store_true', dest='json_output', help='Output results as JSON for machine consumption')
    args = parser.parse_args()

    # When --json is requested, redirect informational prints to stderr
    # so that stdout contains only the JSON result for machine consumption.
    if args.json_output:
        import builtins
        import functools
        builtins.print = functools.partial(builtins.print, file=sys.stderr)

    if not os.path.exists(args.recording):
        print(f"Recording not found: {args.recording}", file=sys.stderr)
        sys.exit(1)

    if not os.path.isdir(args.source_frames):
        print(f"Source frames directory not found: {args.source_frames}", file=sys.stderr)
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix='video-verify-')
    received_dir = os.path.join(work_dir, 'received')
    source_decoded_dir = os.path.join(work_dir, 'source')
    os.makedirs(source_decoded_dir, exist_ok=True)

    try:
        # Phase 2: Extract frames from recording
        print("\n--- Phase 2: Extracting frames from recording ---")
        received_frames = extract_frames(args.recording, received_dir)
        if not received_frames:
            print("No frames extracted!", file=sys.stderr)
            sys.exit(1)

        # Frame count validation
        print("\n--- Frame count validation ---")
        fc_passed, actual_frames, expected_frames, duration = validate_frame_count(args.recording)
        if fc_passed is None:
            print("Could not determine video duration or frame count, skipping validation")
        else:
            print(f"Duration: {duration:.2f}s")
            print(f"Actual frames: {actual_frames}")
            print(f"Expected frames at {FPS} FPS: {expected_frames}")
            diff_pct = abs(actual_frames - expected_frames) / expected_frames * 100 if expected_frames > 0 else 0
            print(f"Difference: {diff_pct:.1f}%")
            if fc_passed:
                print("Frame count: PASS (within 10% tolerance)")
            else:
                print("Frame count: FAIL (exceeds 10% tolerance)")

        # Phase 3: OCR and alignment
        print("\n--- Phase 3: Aligning frames via OCR ---")
        timer_text = ocr_timer(received_frames[0])
        print(f"First frame timer: '{timer_text}'")

        timer_ms = parse_timer(timer_text)
        if timer_ms is None:
            print(f"Failed to parse timer text: '{timer_text}'", file=sys.stderr)
            sys.exit(1)

        start_frame_index = timer_to_frame_index(timer_ms)
        print(f"Timer: {timer_ms}ms -> source frame index: {start_frame_index}")

        # Phase 4: Compare frames
        print(f"\n--- Phase 4: Comparing frames ---")
        num_to_compare = args.max_compare if args.max_compare > 0 else len(received_frames)

        # Build source stream once and extract all needed frames
        source_stream = os.path.join(work_dir, 'source.h264')
        build_source_stream(args.source_frames, source_stream)

        needed_indices = [
            ((start_frame_index - 1 + i * FPS) % TOTAL_SOURCE_FRAMES) + 1
            for i in range(num_to_compare)
        ]
        source_pngs = extract_source_frames(source_stream, source_decoded_dir, set(needed_indices))

        scores = []
        failures = []

        for i in range(num_to_compare):
            source_idx = needed_indices[i]
            source_png = source_pngs.get(source_idx)

            if not source_png:
                print(f"  [{i+1}/{num_to_compare}] Could not decode source frame {source_idx}, skipping")
                continue

            score = compute_ssim(received_frames[i], source_png)
            scores.append(score)

            status = "PASS" if score >= args.threshold else "FAIL"
            if score < args.threshold:
                failures.append((i, source_idx, score))

            if i < 5 or score < args.threshold or args.verbose:
                print(f"  [{i+1}/{num_to_compare}] received frame {i+1} vs source frame-{source_idx:04d}: SSIM={score:.4f} {status}")

        # Summary
        print(f"\n--- Results ---")
        if scores:
            avg = sum(scores) / len(scores)
            min_score = min(scores)
            ssim_failure_pct = (len(failures) / len(scores)) * 100
            frame_loss_pct = 0.0
            if args.expected_duration > 0:
                # Use external duration (e.g. canary run time) as ground truth
                ext_expected = int(args.expected_duration * FPS)
                frame_loss_pct = max(0, (ext_expected - actual_frames) / ext_expected * 100) if ext_expected > 0 else 0
            elif fc_passed is not None and expected_frames and expected_frames > 0:
                frame_loss_pct = max(0, (expected_frames - actual_frames) / expected_frames * 100)

            if args.json_output:
                import json
                json.dump({
                    'ssim_failure_pct': round(ssim_failure_pct, 2),
                    'frame_loss_pct': round(frame_loss_pct, 2),
                    'avg_ssim': round(avg, 4),
                    'min_ssim': round(min_score, 4),
                    'frames_compared': len(scores),
                    'frames_failed': len(failures),
                    'actual_frames': actual_frames,
                    'expected_frames': expected_frames,
                    'duration': round(duration, 2) if duration else None
                }, sys.stdout)
                print()  # newline after JSON
                sys.exit(1 if failures else 0)

            print(f"Frames compared: {len(scores)}")
            print(f"Average SSIM: {avg:.4f}")
            print(f"Min SSIM: {min_score:.4f}")
            print(f"SSIM failure rate: {ssim_failure_pct:.1f}%")
            print(f"Frame loss rate: {frame_loss_pct:.1f}%")
            print(f"Failures (below {args.threshold}): {len(failures)}")

            if failures:
                print(f"\nFailed frames:")
                for i, src_idx, score in failures:
                    print(f"  received frame {i+1} vs source frame-{src_idx:04d}: SSIM={score:.4f}")
                sys.exit(1)
            else:
                print("\nAll frames passed!")
                sys.exit(0)
        else:
            print("No frames compared!", file=sys.stderr)
            sys.exit(1)

    finally:
        if not args.keep_frames:
            shutil.rmtree(work_dir, ignore_errors=True)
        else:
            print(f"\nFrames kept at: {work_dir}")


if __name__ == '__main__':
    main()
