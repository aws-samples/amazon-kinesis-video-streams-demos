#!/usr/bin/env python3
"""Split an Annex-B H.264 elementary stream into one file per Access Unit.

Splits at every AUD (Access Unit Delimiter, NAL type 9). Requires the encoder
to emit AUDs (x264: aud=1). Writes <outdir>/frame-NNNN.h264, 1-indexed.
"""
import os
import sys


def find_start_codes(buf):
    """Yield (offset, start_code_len) for each Annex-B start code."""
    i = 0
    n = len(buf)
    while i < n - 2:
        if i < n - 3 and buf[i] == 0 and buf[i + 1] == 0 and buf[i + 2] == 0 and buf[i + 3] == 1:
            yield (i, 4)
            i += 4
        elif buf[i] == 0 and buf[i + 1] == 0 and buf[i + 2] == 1:
            yield (i, 3)
            i += 3
        else:
            i += 1


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: split_h264_aus.py <input.h264> <outdir>")

    inpath, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    with open(inpath, "rb") as f:
        data = f.read()

    aud_offsets = []
    for off, sc in find_start_codes(data):
        nal_type = data[off + sc] & 0x1F
        if nal_type == 9:
            aud_offsets.append(off)

    if not aud_offsets:
        sys.exit("ERROR: no AUDs found; encoder must emit AUD (x264 aud=1)")

    aud_offsets.append(len(data))

    n_frames = len(aud_offsets) - 1
    for i in range(n_frames):
        au = data[aud_offsets[i]:aud_offsets[i + 1]]
        out = os.path.join(outdir, f"frame-{i + 1:04d}.h264")
        with open(out, "wb") as f:
            f.write(au)

    print(f"wrote {n_frames} frames to {outdir}")


if __name__ == "__main__":
    main()
