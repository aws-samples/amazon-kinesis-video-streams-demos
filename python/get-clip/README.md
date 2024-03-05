# Kinesis Video Streams Long Clip Downloader

This Python script fetches media clips from an Amazon Kinesis Video Stream within a specified time range using the [GetClip API](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_reader_GetClip.html) and saves them as MP4 files. If the requested time range exceeds the size of a single clip, the media will be split into smaller chunks, fetched separately, and optionally merged into a single file.

## Features

- Fetch media clips from a specified time range in an Amazon Kinesis Video stream.
- Split large time ranges into manageable chunks to comply with AWS limitations.
- Merge fetched clips into a single MP4 file using FFmpeg.
- Retry logic for handling API rate limits and transient errors.

## Usage

### Edit the Script:

Open the script in a text editor or IDE and modify the `main` function of `get_clip.py`:

1. Configure credentials. Refer to [the boto3 documentation](https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html#configuring-credentials) for other authentication methods.
```python
session = boto3.Session(aws_access_key_id='YourAccessKey',
                        aws_secret_access_key='YourSecretKey',
                        region_name=AWS_REGION)
```

2. Configure which Kinesis Video Stream to fetch.

```python
stream_name = 'YourStreamName'
```

3. Configure the timestamps to fetch.
```python
fragment_selector_type = 'SERVER_TIMESTAMP'
pst = timezone(timedelta(hours=-8))  # pst = UTC-8
start_timestamp = datetime(2024, 3, 5, 12, 19, 0, tzinfo=pst)
end_timestamp = datetime(2024, 3, 5, 12, 26, 0, tzinfo=pst)
```

4. Configure the chunk length. Depending on the size of your ingested media, you may need to use a smaller chunks.
```python
chunk_size = timedelta(minutes=3)
```

5. Save the file and run the script. The output file will get saved to the current directory.
```python
python3 ./get_clip.py
```

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
