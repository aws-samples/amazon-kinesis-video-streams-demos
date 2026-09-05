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
      3. Sample at most --max-samples of those seconds (bounds wall time on long
         soak segments, which otherwise outlive the caller's timeout)
      4. OCR each sampled frame to read the frame counter
      5. Discard counters that are transient excursions, i.e. OCR misreads: they
         would otherwise be scored against unrelated reference content
      6. Extract only the needed reference frames (by frame number)
      7. Compare each sampled frame against its matching reference frame via SSIM

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
import math
import os
import shutil
import subprocess
import sys
import tempfile
import traceback

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

# Cap on OCR + SSIM samples per verification (see --max-samples). Verification cost is
# per clip second, but the caller's timeout is fixed: the viewer allows 600s
# (VERIFY_TIMEOUT_MS in chrome-headless.js) while a 40 min segment yields ~2400 clip
# seconds, which measured out at roughly 45 min of work. On the 2026-09-03 soak that
# killed 18 of 19 segments mid-SSIM and the whole 16.6h run produced no egress verdict at
# all. 240 samples keeps a 40 min segment near 6 min and leaves the consumer's 60s
# segments (~55 frames) untouched.
MAX_SSIM_SAMPLES = 240

# How far a frame counter may sit from its time neighbours before it is treated as an OCR
# misread (see reject_ocr_outliers). Measured against ground truth, every misread was a
# digit-level error and so landed >= 200 frames (6.7s) away, while real frame-time drift has a
# mode of 0.6s (18 frames). 90 frames (3s) sits in that gap with margin on both sides.
OCR_OUTLIER_TOLERANCE_FRAMES = 90

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


def counter_residual(clip_sec, frame_num):
    """Position of a frame counter relative to a healthy timeline, modulo one source pass.

    For a healthy stream the counter advances FPS per clip second, so counter - FPS*second is
    constant. Taking it modulo TOTAL_SOURCE_FRAMES makes it constant *across* source loops too,
    which matters because a long clip wraps several times.
    """
    return (frame_num - FPS * clip_sec) % TOTAL_SOURCE_FRAMES


def ring_delta(a, b):
    """Signed shortest distance from b to a on the TOTAL_SOURCE_FRAMES ring."""
    half = TOTAL_SOURCE_FRAMES / 2.0
    return (a - b + half) % TOTAL_SOURCE_FRAMES - half


def reject_ocr_outliers(clip_to_ref, tolerance=OCR_OUTLIER_TOLERANCE_FRAMES):
    """Drop frames whose OCR'd counter is a transient excursion. Returns (kept, dropped).

    ocr_frame_number returns a wrong-but-in-range number on roughly 1.7% of frames even on a
    pristine source (measured against ground truth: 515->915, 983->1983, 2373->3373 -- the digit
    OCR inserts or drops a digit, so the error is a multiple of 100 frames). The ocr_failures
    counter cannot see these, because it only rejects unreadable or out-of-range reads. They are
    disproportionately damaging because the verdict uses extremes: min_ssim compares the frame
    against unrelated reference content and collapses toward the availability threshold, and
    max_drift_seconds inflates by 100/FPS seconds or more. At 240 samples the chance of at least
    one misread is 98%, i.e. essentially every verification.

    The test is whether the excursion *comes back*, which is a physical argument rather than a
    statistical one: a stream cannot lose 13s of content and then get it back, so a residual that
    leaves its level and returns to it was never a timing event. Concretely, the samples are cut
    into runs of consecutive reads that agree within `tolerance` -- comparing each read against
    the run's previous read, so arbitrarily large *gradual* drift stays one run -- and a short run
    is dropped when the runs on either side of it agree with each other. Real events survive by
    construction: smooth drift never leaves its run, and a permanent step (the reconnect gap
    between two concatenated viewer segments, say) produces two long runs that do not agree.

    Deliberately not a median/MAD filter over all residuals: with a real step at the midpoint the
    global centre sits between the two levels and half the clip looks like an outlier. Also not
    the single-neighbour form -- OCR misreads the same glyph the same way on consecutive frames,
    so runs of two or three identical misreads are common (observed on a 120s clip at secs 19-20,
    both exactly +400).

    Adjacent short runs at different levels are kept: with nothing stable on both sides there is
    no level to declare a return to. That is the conservative direction (a suspect read survives
    rather than a real event being erased).
    """
    if len(clip_to_ref) < 3:
        return clip_to_ref, []
    residuals = [counter_residual(sec, num) for sec, _, num in clip_to_ref]

    runs = [[0]]
    for i in range(1, len(residuals)):
        if abs(ring_delta(residuals[i], residuals[runs[-1][-1]])) <= tolerance:
            runs[-1].append(i)
        else:
            runs.append([i])

    # A returning excursion is an artefact however long it is, but cap the length anyway so a
    # pathological clip cannot have most of itself discarded by one rule.
    max_spike = max(3, len(residuals) // 10)
    outlier_idx = set()
    for k, run in enumerate(runs):
        if len(run) > max_spike:
            continue
        before = runs[k - 1] if k > 0 else None
        after = runs[k + 1] if k + 1 < len(runs) else None
        if before is not None and after is not None:
            spike = abs(ring_delta(residuals[after[0]], residuals[before[-1]])) <= tolerance
        else:
            # A short run at either end has only one side to lean on; treat it as a spike when
            # that side is a stable level.
            neighbour = after if before is None else before
            spike = neighbour is not None and len(neighbour) > max_spike
        if spike:
            outlier_idx.update(run)

    kept = [f for i, f in enumerate(clip_to_ref) if i not in outlier_idx]
    dropped = [f for i, f in enumerate(clip_to_ref) if i in outlier_idx]
    return kept, dropped


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
        remux = subprocess.run(['ffmpeg', '-y', '-i', video_path, '-c', 'copy', remuxed],
                               capture_output=True, text=True)
        # A stillborn session (audio-only, zero video frames -- seen 10 times on the
        # 2026-09-03 soak when a viewer joined within ~30s of a master reconnect) makes the
        # remux fail, so the file never appears. Removing it unconditionally raised
        # FileNotFoundError out of main() and lost the whole run's metric.
        if remux.returncode != 0 or not os.path.exists(remuxed):
            return None
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


def cached_reference_video(source_dir, cache_path, work_dir):
    """Return a reference video, reusing cache_path when it is still valid.

    The reference is a pure function of the source frames, but rebuilding it dominated the cost of
    a short verification: 18s of a 34s run on a 60s segment locally. The soak consumer verifies a
    60s segment every 60s, so on the node (~2.4x slower) this alone consumed most of the budget and
    is why 141 of 858 segments were skipped for lack of capacity.

    Cache validity is by mtime against the newest source frame, so switching --source-frames to
    another asset set (or editing one) rebuilds. Written via a temp file plus os.replace so a
    concurrent verifier -- the viewer runs its own, and a crash mid-build is possible -- can never
    observe a half-written reference.
    """
    if not cache_path:
        return build_reference_video(source_dir, os.path.join(work_dir, 'reference.mp4'))

    newest_source = 0.0
    for frame in glob.glob(os.path.join(source_dir, 'frame-*.h264')):
        newest_source = max(newest_source, os.path.getmtime(frame))
    if os.path.exists(cache_path) and os.path.getmtime(cache_path) >= newest_source:
        duration = get_video_duration(cache_path)
        # A cache truncated by a crash or a full disk has the right mtime but the wrong length.
        if duration is not None and duration >= 0.9 * EXPECTED_DURATION:
            print(f"Reusing cached reference video: {cache_path} ({duration:.1f}s)")
            return cache_path
        print(f"Cached reference video at {cache_path} is unusable (duration={duration}), rebuilding")

    staging = os.path.join(work_dir, 'reference.mp4')
    if not build_reference_video(source_dir, staging):
        return None
    try:
        os.makedirs(os.path.dirname(os.path.abspath(cache_path)), exist_ok=True)
        os.replace(staging, cache_path)
        print(f"Cached reference video at {cache_path}")
        return cache_path
    except OSError as e:
        # An unwritable cache location must not fail the verification.
        print(f"Could not cache reference video at {cache_path} ({e}); using it in place")
        return staging


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


def emit_failure(msg, json_output, **extra):
    """Emit a storage_availability=0 verdict for a hard failure, then exit.

    Every failure path has to produce a datapoint. The viewer's runVerifyJob publishes
    nothing when it cannot parse a verdict, so a crash used to become a silent hole in the
    coverage record instead of a visible zero -- and CloudWatch treats a missing datapoint
    very differently from a 0. Exits 0 whenever a verdict was written to stdout so the
    caller's execFile resolves and can read it; without --json there is no consumer to keep
    happy, so keep the non-zero status for humans.
    """
    print(msg, file=sys.stderr)
    if json_output:
        json.dump({'storage_availability': 0, 'error': msg, **extra}, sys.stdout)
        sys.stdout.flush()
        sys.exit(0)
    sys.exit(1)


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
    parser.add_argument('--max-samples', type=int, default=MAX_SSIM_SAMPLES,
                        help='Cap on how many clip seconds are OCR-ed and SSIM-compared in '
                             f'ssim mode (default: {MAX_SSIM_SAMPLES}, 0 = no cap). Frames are '
                             'sampled at an even stride, so wall time stays bounded no matter '
                             'how long the segment is.')
    parser.add_argument('--reference-cache', default=None,
                        help='Path to keep the built reference video at, reused across '
                             'invocations while it is newer than the source frames. Rebuilding it '
                             'is over half the cost of verifying a short segment, so a continuous '
                             'soak (one 60s segment per 60s) needs this to keep up.')
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
        emit_failure("No recordings found", args.json_output, segments=0)

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
            seg_frames = count_video_frames(rec)
            # A container can report a duration that is really an absolute timestamp: a soak
            # segment once came back as duration=1788427222.035s (epoch + 5s of content).
            # Cross-check against the decoded frame count, which is timestamp-independent, so
            # a nonsense duration cannot pass the duration threshold or poison the sum.
            if seg_duration is not None and seg_frames > 0 and seg_duration > 2 * seg_frames / FPS + 60:
                print(f"  segment {rec}: duration={seg_duration}s is implausible for {seg_frames} "
                      f"frames, using {seg_frames / FPS:.2f}s")
                seg_duration = seg_frames / FPS
            if seg_duration is not None:
                clip_duration = (clip_duration or 0.0) + seg_duration
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
            emit_failure(
                f"--mode ssim requires a valid --source-frames directory: {args.source_frames}",
                args.json_output, mode='ssim', segments=len(recordings))

        # Phase 1: Build reference video
        print("\n--- Phase 1: Building reference video ---")
        ref_video = cached_reference_video(
            args.source_frames, args.reference_cache, work_dir)
        if not ref_video:
            emit_failure("Failed to build reference video", args.json_output,
                         mode='ssim', clip_duration=clip_duration, segments=len(recordings))

        # Phase 3: Extract 1fps from every segment into a combined frame list.
        # Order across segments doesn't matter — OCR maps each frame back to its
        # source frame number independently.
        print("\n--- Phase 3: Extracting clip frames at 1 FPS ---")
        # (clip_sec, path). clip_sec is the 1-based second into the concatenated timeline,
        # carried explicitly rather than derived from list position so that sampling below
        # cannot shift it -- the drift math in Phase 6b is in real seconds.
        clip_frames = []
        for idx, rec in enumerate(recordings):
            for path in extract_frames_1fps(rec, os.path.join(work_dir, f'clip-{idx}')):
                clip_frames.append((len(clip_frames) + 1, path))
        if not clip_frames:
            # Reached when the clip has no decodable video at all, and also when a segment
            # carries absolute-epoch PTS (observed duration=1788427222.035s) -- see the
            # frame-index extraction note in extract_frames_1fps.
            emit_failure("Failed to extract clip frames", args.json_output,
                         mode='ssim', clip_duration=clip_duration,
                         frames_decoded=clip_total_frames, segments=len(recordings))

        # Verification cost scales with clip length but the caller's timeout does not, so
        # sample at an even stride to bound wall time. See MAX_SSIM_SAMPLES. Done before
        # OCR because OCR is as expensive as the SSIM pass itself.
        clip_seconds_total = len(clip_frames)
        if args.max_samples and clip_seconds_total > args.max_samples:
            stride = math.ceil(clip_seconds_total / args.max_samples)
            clip_frames = clip_frames[::stride]
            print(f"Sampling every {stride}s: {len(clip_frames)} of {clip_seconds_total} "
                  f"clip seconds (--max-samples {args.max_samples})")

        # Phase 4: OCR the sampled clip frames to get frame numbers
        print(f"\n--- Phase 4: OCR {len(clip_frames)} clip frames ---")
        clip_to_ref = []  # list of (clip_sec, clip_frame_path, ref_frame_number)
        ocr_failures = 0
        for i, (clip_sec, clip_frame) in enumerate(clip_frames):
            frame_num = ocr_frame_number(clip_frame)
            if frame_num is None or frame_num < 1 or frame_num > TOTAL_SOURCE_FRAMES:
                if i < 5 or args.verbose:
                    print(f"  [clip sec {clip_sec}] OCR: {frame_num} — skipping")
                ocr_failures += 1
                continue
            clip_to_ref.append((clip_sec, clip_frame, frame_num))
            if i < 5 or args.verbose:
                print(f"  [clip sec {clip_sec}] frame #{frame_num}")

        print(f"OCR complete: {len(clip_to_ref)} matched, {ocr_failures} failed")

        # Phase 4b: drop OCR misreads before they reach either SSIM or the drift math. Rejected
        # here rather than filtered later so the reference frames they point at are never even
        # extracted. See reject_ocr_outliers for why ocr_failures does not already cover this.
        clip_to_ref, ocr_outliers = reject_ocr_outliers(clip_to_ref)
        if ocr_outliers:
            print(f"Rejected {len(ocr_outliers)} OCR outlier(s): counter left its level by "
                  f"> {OCR_OUTLIER_TOLERANCE_FRAMES / FPS:.1f}s and came back")
            for sec, _, num in ocr_outliers[:10]:
                print(f"  [clip sec {sec}] frame #{num}")

        # Phase 5: Extract only the needed reference frames
        needed_frames = set(num for _, _, num in clip_to_ref)
        print(f"\n--- Phase 5: Extracting {len(needed_frames)} reference frames ---")
        ref_frame_map = extract_specific_frames(
            ref_video, os.path.join(work_dir, 'ref'), needed_frames)

        # Phase 6: SSIM comparison
        print(f"\n--- Phase 6: SSIM comparison ---")
        scores = []
        for clip_sec, clip_frame, ref_num in clip_to_ref:
            ref_path = ref_frame_map.get(ref_num)
            if not ref_path:
                continue
            score = compute_ssim(clip_frame, ref_path)
            scores.append(score)
            if clip_sec <= 5 or score < 0.9 or args.verbose:
                print(f"  [clip sec {clip_sec}] frame #{ref_num} -> SSIM={score:.4f}")

        # Phase 6b: Temporal drift. SSIM aligns clip<->source by the OCR'd counter, so it is blind
        # to *timing*. Measure how the frame-counter timeline diverges from the clip's real time:
        # each frame carries its real clip-second (not its position in the sampled list, so
        # --max-samples does not distort this), and for a healthy stream the counter advances by
        # FPS per clip-second, hence
        #     drift(t) = (counter - counter0)/FPS - (t - t0)
        # anchored at the earliest matched frame. Growing |drift| = producer/pipeline time drift
        # (the Verisure timestamp-drift class) that SSIM cannot see. Reported as avg/max seconds; a
        # separate signal from storage_availability (which stays content-only).
        drift_samples = []
        if len(clip_to_ref) >= 2:
            ordered = sorted((sec, num) for sec, _, num in clip_to_ref)
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
                      'ocr_outliers': len(ocr_outliers),
                      'clip_seconds_total': clip_seconds_total,
                      'clip_seconds_sampled': len(clip_frames),
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
            print(f"OCR outliers:       {len(ocr_outliers)}")
            print(f"Storage available:  {available}")

            result = {
                'storage_availability': available,
                'mode': 'ssim',
                'max_ssim': round(max_ssim, 4),
                'avg_ssim': round(avg_ssim, 4),
                'min_ssim': round(min_ssim, 4),
                'frames_compared': len(scores),
                # ocr_failures: unreadable or out-of-range. ocr_outliers: read cleanly but
                # inconsistent with its neighbours, i.e. the silent misreads. Both are OCR
                # health, and only together do they add up to it.
                'ocr_failures': ocr_failures,
                'ocr_outliers': len(ocr_outliers),
                # How much of the clip the verdict actually looked at. Without this a
                # sampled verdict and a full one are indistinguishable after the fact.
                'clip_seconds_total': clip_seconds_total,
                'clip_seconds_sampled': len(clip_frames),
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
    try:
        main()
    except Exception:
        # Last resort so an unanticipated crash is still a visible zero rather than a
        # missing datapoint (see emit_failure). argv is inspected directly because the
        # crash may predate argument parsing. SystemExit is a BaseException and so still
        # propagates untouched.
        traceback.print_exc()
        emit_failure('verify.py crashed: ' + traceback.format_exc(limit=0).strip().splitlines()[-1],
                     '--json' in sys.argv)
