"""
    Constants for get_clip.py
"""

class Constants:
    """
        Constants for get_clip.py
    """
    # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_GetDataEndpoint.html
    RETRYABLE_EXCEPTIONS = ['ClientLimitExceededException', 'InternalFailure']
    MAX_RETRIES = 5
    # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_Operations_Amazon_Kinesis_Video_Streams.html
    KINESIS_VIDEO_SERVICE_NAME = 'kinesisvideo'
    # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_Operations_Amazon_Kinesis_Video_Streams_Archived_Media.html
    KINESIS_VIDEO_ARCHIVED_MEDIA_SERVICE_NAME = 'kinesis-video-archived-media'
    # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/limits.html
    MAX_REQUESTS_PER_SECOND = 5

    TIMESTAMP_SELECTOR_CHOICES = ["SERVER_TIMESTAMP", "PRODUCER_TIMESTAMP"]
    DEFAULT_TIMESTAMP_SELECTOR_CHOICE = "SERVER_TIMESTAMP"

    DEFAULT_TIMEZONE_OFFSET_HOURS = 0

    DEFAULT_CHUNK_SIZE_MINUTES = 3
