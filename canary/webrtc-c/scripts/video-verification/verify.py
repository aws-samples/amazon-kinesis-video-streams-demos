#!/usr/bin/env python3
"""
Video verification script for WebRTC storage canary.

Verifies the received video (GetClip or viewer recording) to determine
StorageAvailability. Two modes:

  ssim (default) — deterministic sources (disk / framesrc): the stream carries
    the repo's sample frames with a burned-in frame counter, so we can rebuild a
    reference and score each clip frame against its matching source frame:
      1. Build a reference video from the raw H.264 sample frames
      2. Extract 1fps from the received clip
      3. OCR each clip frame to read the frame counter
      4. Extract only the needed reference frames (by frame number)
      5. Compare each clip frame against its matching reference frame via SSIM

  presence — live / non-deterministic sources (camera / filesrc / testsrc):
    there is no frame-counter reference to OCR-match, so per-frame SSIM is
    pointless. Instead we only confirm the clip is a decodable video of roughly
    the right length (duration + frame count). TWCC varies bitrate, not frame
    rate, so a healthy ingest still delivers ~FPS*duration frames even under
    congestion.

ssim thresholds (matching VideoVerificationComponent.java):
  - Duration >= 120 seconds
  - Max SSIM > 0.99
  - Avg SSIM > 0.85
  - Min SSIM > 0.03
  - Clip frame count >= TOTAL_SOURCE_FRAMES - 1500

Usage:
  python verify.py --recording clip.mp4 --source-frames ../../assets/h264SampleFrames --json
  python verify.py --recording clip.mp4 --mode presence --expected-duration 600 --json
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

# Presence-mode thresholds (no frame-by-frame comparison): the clip just has to
# be a decodable video of roughly the right length. TWCC varies bitrate, not
# frame rate, so a healthy ingest still delivers ~FPS*duration frames.
PRESENCE_DURATION_FRACTION = 0.8   # clip must cover >= 80% of the expected run
PRESENCE_FRAME_FRACTION = 0.5      # and decode >= 50% of FPS*duration frames

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


def count_video_frames(video_path):
    """Count decoded video frames in a clip via ffprobe. Returns int (0 on error)."""
    fc_cmd = [
        'ffprobe', '-v', 'error', '-select_streams', 'v:0',
        '-count_frames', '-show_entries', 'stream=nb_read_frames',
        '-of', 'default=noprint_wrappers=1:nokey=1', video_path
    ]
    fc_result = subprocess.run(fc_cmd, capture_output=True, text=True)
    try:
        return int(fc_result.stdout.strip())
    except ValueError:
        return 0


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
        description='Verify received video for StorageAvailability')
    parser.add_argument('--recording', required=True, nargs='+',
                        help='Path(s) to received video. A viewer session that reconnects '
                             'produces one recording segment per connection; pass all of '
                             'them and they are verified as one logical recording '
                             '(durations and frame counts are summed before thresholds).')
    parser.add_argument('--source-frames', required=False, default=None,
                        help='Path to H.264 source frames directory (required for --mode ssim; '
                             'ignored for --mode presence)')
    parser.add_argument('--mode', choices=['ssim', 'presence'], default='ssim',
                        help="ssim = frame-by-frame SSIM vs the source frames (deterministic "
                             "sources: disk/framesrc). presence = decodable-video + duration/"
                             "frame-count only, no per-frame comparison (live/non-deterministic "
                             "sources: camera/filesrc/testsrc, which have no frame-counter reference).")
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

    recordings = []
    for rec in args.recording:
        if os.path.exists(rec):
            recordings.append(rec)
        else:
            print(f"Recording not found (skipping): {rec}", file=sys.stderr)
    if not recordings:
        print("No recordings found", file=sys.stderr)
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix='video-verify-')
    try:
        # --- Duration and frame count (both modes) ---
        # Summed across all segments so a reconnected session (multiple files) is
        # judged against the same session-wide thresholds as one uninterrupted
        # recording.
        print("\n--- Duration and frame count ---")
        expected = args.expected_duration
        clip_duration = None
        clip_total_frames = 0
        for rec in recordings:
            seg_duration = get_video_duration(rec)
            if seg_duration is not None:
                clip_duration = (clip_duration or 0.0) + seg_duration
            seg_frames = count_video_frames(rec)
            clip_total_frames += seg_frames
            print(f"  segment {rec}: duration={seg_duration if seg_duration is not None else 'N/A'}s frames={seg_frames}")

        print(f"Segments:           {len(recordings)}")
        print(f"Clip duration:      {clip_duration:.2f}s" if clip_duration is not None else "Clip duration:      N/A")
        print(f"Clip total frames:  {clip_total_frames}")
        print(f"Expected duration:  {expected:.2f}s")

        # --- Presence mode: decodable video of roughly the right length, no
        # frame-by-frame comparison (live/non-deterministic sources) ---
        if args.mode == 'presence':
            print("\n--- Presence check (no frame-by-frame comparison) ---")
            duration_threshold = expected * PRESENCE_DURATION_FRACTION
            min_frames = int(FPS * expected * PRESENCE_FRAME_FRACTION)
            duration_ok = clip_duration is not None and clip_duration >= duration_threshold
            frames_ok = clip_total_frames >= min_frames
            available = 1 if (duration_ok and frames_ok) else 0

            dur_str = f"{clip_duration:.2f}s" if clip_duration is not None else "N/A"
            print(f"Duration:           {dur_str} ({'PASS' if duration_ok else 'FAIL'} — threshold: >= {duration_threshold:.1f}s)")
            print(f"Clip frames:        {clip_total_frames} ({'PASS' if frames_ok else 'FAIL'} — threshold: >= {min_frames})")
            print(f"Storage available:  {available}")

            result = {
                'storage_availability': available,
                'mode': 'presence',
                'frames_decoded': clip_total_frames,
                'clip_duration': round(clip_duration, 2) if clip_duration else None,
                'expected_duration': round(expected, 2),
                'segments': len(recordings),
            }
            if args.json_output:
                json.dump(result, sys.stdout)
                print()
            sys.exit(0)

        # --- SSIM mode (deterministic sources: disk / framesrc) ---
        if not args.source_frames or not os.path.isdir(args.source_frames):
            print(f"--mode ssim requires a valid --source-frames directory: {args.source_frames}",
                  file=sys.stderr)
            sys.exit(1)

        # Phase 1: Build reference video
        print("\n--- Phase 1: Building reference video ---")
        ref_video = build_reference_video(
            args.source_frames, os.path.join(work_dir, 'reference.mp4'))
        if not ref_video:
            print("Failed to build reference video", file=sys.stderr)
            sys.exit(1)

        # Phase 3: Extract 1fps from every segment into a combined frame list.
        # Order across segments doesn't matter — OCR maps each frame back to its
        # source frame number independently.
        print("\n--- Phase 3: Extracting clip frames at 1 FPS ---")
        clip_frames = []
        for idx, rec in enumerate(recordings):
            clip_frames.extend(
                extract_frames_1fps(rec, os.path.join(work_dir, f'clip-{idx}')))
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

        # Phase 6b: Temporal drift. SSIM aligns clip<->source by the OCR'd counter, so it is blind
        # to *timing*. Measure how the frame-counter timeline diverges from the clip's real time:
        # clip frames are sampled at 1 fps, so a frame's clip-second is its index, and for a healthy
        # stream the counter advances by FPS per clip-second, hence
        #     drift(t) = (counter - counter0)/FPS - (t - t0)
        # anchored at the earliest matched frame. Growing |drift| = producer/pipeline time drift
        # (the Verisure timestamp-drift class) that SSIM cannot see. Reported as avg/max seconds; a
        # separate signal from storage_availability (which stays content-only).
        drift_samples = []
        if len(clip_to_ref) >= 2:
            ordered = sorted((clip_frames.index(cf), num) for cf, num in clip_to_ref)
            t0, c0 = ordered[0]
            prev_c = c0
            for t, c in ordered[1:]:
                if c < prev_c:
                    # Source frames loop 1..TOTAL_SOURCE_FRAMES; a counter decrease means the
                    # stream wrapped to a new pass (common when verifying short soak segments).
                    # Re-anchor rather than treating the wrap as a huge negative drift.
                    t0, c0 = t, c
                    prev_c = c
                    continue
                prev_c = c
                drift_samples.append(abs((c - c0) / FPS - (t - t0)))
        avg_drift_seconds = round(sum(drift_samples) / len(drift_samples), 3) if drift_samples else 0.0
        max_drift_seconds = round(max(drift_samples), 3) if drift_samples else 0.0
        print(f"Avg frame-time drift: {avg_drift_seconds}s")
        print(f"Max frame-time drift: {max_drift_seconds}s")

        # Phase 7: Compute availability
        print("\n--- Results ---")
        if not scores:
            print("No frames compared!", file=sys.stderr)
            result = {'storage_availability': 0, 'mode': 'ssim',
                      'clip_duration': clip_duration,
                      'frames_compared': 0, 'ocr_failures': ocr_failures,
                      'avg_drift_seconds': avg_drift_seconds, 'max_drift_seconds': max_drift_seconds,
                      'segments': len(recordings)}
        else:
            avg_ssim = sum(scores) / len(scores)
            max_ssim = max(scores)
            min_ssim = min(scores)

            # Duration and frame-count thresholds scale with --expected-duration so short soak
            # segments (e.g. 60s) are judged proportionally. At the default expected duration
            # (one full source pass, ~156s) these reduce exactly to the original fixed
            # thresholds: >=120s and >= TOTAL_SOURCE_FRAMES - DROPPED_FRAME_THRESHOLD.
            duration_threshold = expected * (120.0 / EXPECTED_DURATION)
            frames_threshold = FPS * expected - DROPPED_FRAME_THRESHOLD * (expected / EXPECTED_DURATION)
            duration_ok = clip_duration is not None and clip_duration >= duration_threshold
            max_ssim_ok = max_ssim > 0.99
            avg_ssim_ok = avg_ssim > 0.85
            min_ssim_ok = min_ssim > 0.03
            frames_ok = clip_total_frames >= frames_threshold
            available = 1 if (duration_ok and max_ssim_ok and avg_ssim_ok and min_ssim_ok and frames_ok) else 0

            print(f"Duration:           {clip_duration:.2f}s ({'PASS' if duration_ok else 'FAIL'} — threshold: >= {duration_threshold:.1f}s)")
            print(f"Max SSIM:           {max_ssim:.4f} ({'PASS' if max_ssim_ok else 'FAIL'} — threshold: > 0.99)")
            print(f"Avg SSIM:           {avg_ssim:.4f} ({'PASS' if avg_ssim_ok else 'FAIL'} — threshold: > 0.85)")
            print(f"Min SSIM:           {min_ssim:.4f} ({'PASS' if min_ssim_ok else 'FAIL'} — threshold: > 0.03)")
            print(f"Clip frames:        {clip_total_frames} ({'PASS' if frames_ok else 'FAIL'} — threshold: >= {frames_threshold:.0f})")
            print(f"SSIM comparisons:   {len(scores)}")
            print(f"OCR failures:       {ocr_failures}")
            print(f"Storage available:  {available}")

            result = {
                'storage_availability': available,
                'mode': 'ssim',
                'max_ssim': round(max_ssim, 4),
                'avg_ssim': round(avg_ssim, 4),
                'min_ssim': round(min_ssim, 4),
                'frames_compared': len(scores),
                'ocr_failures': ocr_failures,
                'avg_drift_seconds': avg_drift_seconds,
                'max_drift_seconds': max_drift_seconds,
                'clip_duration': round(clip_duration, 2) if clip_duration else None,
                'expected_duration': round(expected, 2),
                'segments': len(recordings),
            }

        if args.json_output:
            json.dump(result, sys.stdout)
            print()
        sys.exit(0)

    finally:
        if not args.keep_frames:
            shutil.rmtree(work_dir, ignore_errors=True)
        else:
            print(f"\nFrames kept at: {work_dir}")


if __name__ == '__main__':
    main()
