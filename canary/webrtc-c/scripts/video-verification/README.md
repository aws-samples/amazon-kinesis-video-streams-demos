# Video Verification

Compares the viewer's recorded video against the source H.264 frames sent by the C master to verify visual quality through SSIM scoring.

## Prerequisites

```bash
brew install tesseract ffmpeg
pip install -r requirements.txt
```

## Usage

```bash
# Run from the scripts/ directory after chrome-headless.js has produced a recording
python video-verification/verify.py \
  --recording recordings/viewer-xxx.mp4 \
  --source-frames ../assets/h264SampleFrames

# Compare more frames with a stricter threshold
python video-verification/verify.py \
  --recording recordings/viewer-xxx.mp4 \
  --source-frames ../assets/h264SampleFrames \
  --max-compare 200 \
  --threshold 0.7

# Keep extracted frames for manual inspection
python video-verification/verify.py \
  --recording recordings/viewer-xxx.mp4 \
  --source-frames ../assets/h264SampleFrames \
  --keep-frames
```

## How it works

1. Extracts frames from the viewer recording using ffmpeg
2. OCRs the timer overlay from the first frame to determine alignment
3. Maps each received frame to the corresponding source frame (25 FPS, 1500 frame loop)
4. Computes SSIM (Structural Similarity Index) per frame pair
5. Reports pass/fail based on the SSIM threshold
