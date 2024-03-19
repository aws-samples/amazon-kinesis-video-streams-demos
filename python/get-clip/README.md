# Kinesis Video Streams Long Clip Downloader

This Python script fetches media clips from an Amazon Kinesis Video Stream within a specified time range using the [GetClip API](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_reader_GetClip.html) and saves them as MP4 files. If the requested time range exceeds the size of a single clip, the media will be split into smaller chunks, fetched separately, and optionally merged into a single file.

## Features

- Fetch media clips from a specified time range in an Amazon Kinesis Video stream.
- Split large time ranges into manageable chunks to comply with AWS limitations.
- Merge fetched clips into a single MP4 file using FFmpeg.
- Retry logic for handling API rate limits and transient errors.

## Usage

**Install dependencies**. See [Dependencies](#dependencies).

**Configure credentials and region**. See the [the boto3 documentation](https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html#configuring-credentials) for the order in which boto3 searches for credentials.
```shell
aws configure
```

Use `-h` or `--help` for the full list of arguments.
```shell
python3 ./get_clip.py -h
```

### Sample command

This command fetches the video from `demo-stream` from `2024-03-14T01:17:35-07:00` to `2024-03-14T01:30:35-07:00`.
```shell
python3 ./get_clip.py --stream-name demo-stream --from 2024-03-14T01:17:35 --to 2024-03-14T01:30:35 --tz-offset -7
```

<details><summary>Sample logs</summary>

#### Default chunk size

```shell
python3 ./get_clip.py --stream-name demo-stream --from 2024-03-14T01:17:35 --to 2024-03-14T01:30:35 --tz-offset -7
```
```text
Fetching 2024-03-14 01:17:35-07:00 to 2024-03-14 01:20:35-07:00 for demo-stream.
Received 40.84 MB from Kinesis Video Streams
Fetching 2024-03-14 01:20:35-07:00 to 2024-03-14 01:23:35-07:00 for demo-stream.
Received 17.47 MB from Kinesis Video Streams
Fetching 2024-03-14 01:23:35-07:00 to 2024-03-14 01:26:35-07:00 for demo-stream.
Received 2.53 MB from Kinesis Video Streams
Fetching 2024-03-14 01:26:35-07:00 to 2024-03-14 01:29:35-07:00 for demo-stream.
Received 7.52 MB from Kinesis Video Streams
Fetching 2024-03-14 01:29:35-07:00 to 2024-03-14 01:30:35-07:00 for demo-stream.
Received 45.21 MB from Kinesis Video Streams
Merging 5 files!
ffmpeg version 6.1.1 Copyright (c) 2000-2023 the FFmpeg developers
  built with Apple clang version 15.0.0 (clang-1500.1.0.2.5)
  configuration: --prefix=/opt/homebrew/Cellar/ffmpeg/6.1.1_3 --enable-shared --enable-pthreads --enable-version3 --cc=clang --host-cflags= --host-ldflags='-Wl,-ld_classic' --enable-ffplay --enable-gnutls --enable-gpl --enable-libaom --enable-libaribb24 --enable-libbluray --enable-libdav1d --enable-libharfbuzz --enable-libjxl --enable-libmp3lame --enable-libopus --enable-librav1e --enable-librist --enable-librubberband --enable-libsnappy --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtesseract --enable-libtheora --enable-libvidstab --enable-libvmaf --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-lzma --enable-libfontconfig --enable-libfreetype --enable-frei0r --enable-libass --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenjpeg --enable-libopenvino --enable-libspeex --enable-libsoxr --enable-libzmq --enable-libzimg --disable-libjack --disable-indev=jack --enable-videotoolbox --enable-audiotoolbox --enable-neon
  libavutil      58. 29.100 / 58. 29.100
  libavcodec     60. 31.102 / 60. 31.102
  libavformat    60. 16.100 / 60. 16.100
  libavdevice    60.  3.100 / 60.  3.100
  libavfilter     9. 12.100 /  9. 12.100
  libswscale      7.  5.100 /  7.  5.100
  libswresample   4. 12.100 /  4. 12.100
  libpostproc    57.  3.100 / 57.  3.100
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x13f704a20] Auto-inserting h264_mp4toannexb bitstream filter
Input #0, concat, from 'input_files_list.txt':
  Duration: N/A, start: 0.000000, bitrate: 1810 kb/s
  Stream #0:0(eng): Video: h264 (High 4:4:4 Predictive) (avc1 / 0x31637661), yuv444p10le(tv, smpte170m, progressive), 640x480 [SAR 1:1 DAR 4:3], 1810 kb/s, 9.98 fps, 120 tbr, 1k tbn
    Metadata:
      creation_time   : 2024-03-15T07:12:13.000000Z
      handler_name    : USP Video Handler
      vendor_id       : [0][0][0][0]
Output #0, mp4, to 'demo-stream-2024-03-14 01:17:35-07:00-2024-03-14 01:30:35-07:00.mp4':
  Metadata:
    encoder         : Lavf60.16.100
  Stream #0:0(eng): Video: h264 (High 4:4:4 Predictive) (avc1 / 0x31637661), yuv444p10le(tv, smpte170m, progressive), 640x480 [SAR 1:1 DAR 4:3], q=2-31, 1810 kb/s, 9.98 fps, 120 tbr, 16k tbn
    Metadata:
      creation_time   : 2024-03-15T07:12:13.000000Z
      handler_name    : USP Video Handler
      vendor_id       : [0][0][0][0]
Stream mapping:
  Stream #0:0 -> #0:0 (copy)
Press [q] to stop, [?] for help
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x13f6068c0] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x12f604260] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x13f7048c0] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x105104080] Auto-inserting h264_mp4toannexb bitstream filter
[out#0/mp4 @ 0x6000027a8480] video:110851kB audio:0kB subtitle:0kB other streams:0kB global headers:0kB muxing overhead: 0.029151%
size=  110883kB time=00:08:21.38 bitrate=1811.7kbits/s speed=3.81e+03x    
Finished merging 5 files!
```

#### Smaller chunk size

```shell
python3 ./get_clip.py --stream-name demo-stream --from 2024-03-14T01:17:35 --to 2024-03-14T01:30:35 --tz-offset -7 --chunk-size-minutes 1
```
```text
python3 ./get_clip.py --stream-name YourStreamName2 --from 2024-03-14T01:17:35 --to 2024-03-14T01:30:35 --tz-offset -7 --chunk-size-minutes 1
Fetching 2024-03-14 01:17:35-07:00 to 2024-03-14 01:18:35-07:00 for demo-stream.
Received 13.60 MB from Kinesis Video Streams
Fetching 2024-03-14 01:18:35-07:00 to 2024-03-14 01:19:35-07:00 for demo-stream.
Received 13.61 MB from Kinesis Video Streams
Fetching 2024-03-14 01:19:35-07:00 to 2024-03-14 01:20:35-07:00 for demo-stream.
Received 13.63 MB from Kinesis Video Streams
Fetching 2024-03-14 01:20:35-07:00 to 2024-03-14 01:21:35-07:00 for demo-stream.
Received 13.61 MB from Kinesis Video Streams
Fetching 2024-03-14 01:21:35-07:00 to 2024-03-14 01:22:35-07:00 for demo-stream.
Received 3.86 MB from Kinesis Video Streams
Fetching 2024-03-14 01:22:35-07:00 to 2024-03-14 01:23:35-07:00 for demo-stream.
Error Code: ResourceNotFoundException
Error Message: No fragments found in the stream for the clip request.
Request ID: a49b5a22-0e2a-4149-9ca9-a726f9efe3de
Http status code: 404
No media found between 2024-03-14 01:22:35-07:00 to 2024-03-14 01:23:35-07:00
Fetching 2024-03-14 01:23:35-07:00 to 2024-03-14 01:24:35-07:00 for demo-stream.
Error Code: ResourceNotFoundException
Error Message: No fragments found in the stream for the clip request.
Request ID: 969cb898-dae1-4dcf-a5de-05052688ea44
Http status code: 404
No media found between 2024-03-14 01:23:35-07:00 to 2024-03-14 01:24:35-07:00
Fetching 2024-03-14 01:24:35-07:00 to 2024-03-14 01:25:35-07:00 for demo-stream.
Error Code: ResourceNotFoundException
Error Message: No fragments found in the stream for the clip request.
Request ID: 0bfbcc57-6cb5-4b1d-a1e1-0ff4e794a0e4
Http status code: 404
No media found between 2024-03-14 01:24:35-07:00 to 2024-03-14 01:25:35-07:00
Fetching 2024-03-14 01:25:35-07:00 to 2024-03-14 01:26:35-07:00 for demo-stream.
Received 2.53 MB from Kinesis Video Streams
Fetching 2024-03-14 01:26:35-07:00 to 2024-03-14 01:27:35-07:00 for demo-stream.
Error Code: ResourceNotFoundException
Error Message: No fragments found in the stream for the clip request.
Request ID: a7b1dcb6-7787-4a73-875f-edb7c111f433
Http status code: 404
No media found between 2024-03-14 01:26:35-07:00 to 2024-03-14 01:27:35-07:00
Fetching 2024-03-14 01:27:35-07:00 to 2024-03-14 01:28:35-07:00 for demo-stream.
Received 4.79 MB from Kinesis Video Streams
Fetching 2024-03-14 01:28:35-07:00 to 2024-03-14 01:29:35-07:00 for demo-stream.
Received 2.72 MB from Kinesis Video Streams
Fetching 2024-03-14 01:29:35-07:00 to 2024-03-14 01:30:35-07:00 for demo-stream.
Received 45.21 MB from Kinesis Video Streams
Merging 9 files!
ffmpeg version 6.1.1 Copyright (c) 2000-2023 the FFmpeg developers
  built with Apple clang version 15.0.0 (clang-1500.1.0.2.5)
  configuration: --prefix=/opt/homebrew/Cellar/ffmpeg/6.1.1_3 --enable-shared --enable-pthreads --enable-version3 --cc=clang --host-cflags= --host-ldflags='-Wl,-ld_classic' --enable-ffplay --enable-gnutls --enable-gpl --enable-libaom --enable-libaribb24 --enable-libbluray --enable-libdav1d --enable-libharfbuzz --enable-libjxl --enable-libmp3lame --enable-libopus --enable-librav1e --enable-librist --enable-librubberband --enable-libsnappy --enable-libsrt --enable-libssh --enable-libsvtav1 --enable-libtesseract --enable-libtheora --enable-libvidstab --enable-libvmaf --enable-libvorbis --enable-libvpx --enable-libwebp --enable-libx264 --enable-libx265 --enable-libxml2 --enable-libxvid --enable-lzma --enable-libfontconfig --enable-libfreetype --enable-frei0r --enable-libass --enable-libopencore-amrnb --enable-libopencore-amrwb --enable-libopenjpeg --enable-libopenvino --enable-libspeex --enable-libsoxr --enable-libzmq --enable-libzimg --disable-libjack --disable-indev=jack --enable-videotoolbox --enable-audiotoolbox --enable-neon
  libavutil      58. 29.100 / 58. 29.100
  libavcodec     60. 31.102 / 60. 31.102
  libavformat    60. 16.100 / 60. 16.100
  libavdevice    60.  3.100 / 60.  3.100
  libavfilter     9. 12.100 /  9. 12.100
  libswscale      7.  5.100 /  7.  5.100
  libswresample   4. 12.100 /  4. 12.100
  libpostproc    57.  3.100 / 57.  3.100
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x151e06cc0] Auto-inserting h264_mp4toannexb bitstream filter
Input #0, concat, from 'input_files_list.txt':
  Duration: N/A, start: 0.000000, bitrate: 1808 kb/s
  Stream #0:0(eng): Video: h264 (High 4:4:4 Predictive) (avc1 / 0x31637661), yuv444p10le(tv, smpte170m, progressive), 640x480 [SAR 1:1 DAR 4:3], 1808 kb/s, 9.98 fps, 120 tbr, 1k tbn
    Metadata:
      creation_time   : 2024-03-15T07:19:52.000000Z
      handler_name    : USP Video Handler
      vendor_id       : [0][0][0][0]
Output #0, mp4, to 'demo-stream-2024-03-14 01:17:35-07:00-2024-03-14 01:30:35-07:00.mp4':
  Metadata:
    encoder         : Lavf60.16.100
  Stream #0:0(eng): Video: h264 (High 4:4:4 Predictive) (avc1 / 0x31637661), yuv444p10le(tv, smpte170m, progressive), 640x480 [SAR 1:1 DAR 4:3], q=2-31, 1808 kb/s, 9.98 fps, 120 tbr, 16k tbn
    Metadata:
      creation_time   : 2024-03-15T07:19:52.000000Z
      handler_name    : USP Video Handler
      vendor_id       : [0][0][0][0]
Stream mapping:
  Stream #0:0 -> #0:0 (copy)
Press [q] to stop, [?] for help
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x151f04e60] Auto-inserting h264_mp4toannexb bitstream filter
    Last message repeated 2 times
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x153004080] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x151e06cc0] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x151f04e60] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x151e06cc0] Auto-inserting h264_mp4toannexb bitstream filter
[mov,mp4,m4a,3gp,3g2,mj2 @ 0x153004080] Auto-inserting h264_mp4toannexb bitstream filter
[out#0/mp4 @ 0x600002e2c840] video:110851kB audio:0kB subtitle:0kB other streams:0kB global headers:0kB muxing overhead: 0.029123%
size=  110883kB time=00:08:21.36 bitrate=1811.8kbits/s speed=2.59e+03x    
Finished merging 9 files!
```

</details>


## Dependencies

This Python script relies on the following AWS dependencies:

- `boto3`: AWS SDK for Python.
- `botocore`: Library for handling exceptions and low-level API interactions in Boto3.

Please follow the [boto3 installation instructions](https://boto3.amazonaws.com/v1/documentation/api/latest/guide/quickstart.html):
```shell
pip install boto3
```

This Python script also requires `FFmpeg` to be available on your system's `PATH`. You can learn more about FFmpeg [here](https://www.ffmpeg.org/).

## Error Handling

The function handles various error scenarios gracefully:

- **Client Errors:** Logs error details and retries for certain exceptions specified in `retryable_exceptions`.
- **Non-retryable Errors:** Raises the exception for non-retryable errors, such as a missing permission error (AccessDeniedException).

## Notes

- The AWS IAM user or role must have `kinesisvideo:GetDataEndpoint` and `kinesisvideo:GetClip` permissions.
- Limits for the [GetClip API](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_reader_GetClip.html) can be found on the [Kinesis Video Streams Limits](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/limits.html) page.
- The script includes a retry mechanism to handle throttling. Adjust `MAX_RETRIES` and sleep intervals if needed.
- This script was made and tested with Python 3.12.

## License

This Python script is released under the [Apache 2.0 License](../../LICENSE).
