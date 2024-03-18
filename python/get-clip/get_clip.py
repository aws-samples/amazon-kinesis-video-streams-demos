import argparse
import os
import subprocess
import time
import random
from datetime import datetime, timedelta, timezone

import boto3
import botocore

from constants import Constants

def fetch_clip(session: boto3.session, stream_name: str, start_timestamp: datetime, end_timestamp: datetime,
               chunk_size: timedelta, fragment_selector_type: str) -> list[str]:
    """
    Fetches clips from a Kinesis Video stream within a specified time range.
    If the time range is larger than the chunk size, the clips will be split into files of chunk_size.
    The files names of where the clips are downloaded will be returned.

    Args:
        session (boto3.session): The boto3 session object for accessing AWS services.
        stream_name (str): The name of the Kinesis video stream.
        start_timestamp (time): The start timestamp of the clip.
        end_timestamp (time): The end timestamp of the clip.
        chunk_size (timedelta): The size of each chunk to fetch.
        fragment_selector_type (str): The origin of the timestamps to use (SERVER_TIMESTAMP or PRODUCER_TIMESTAMP).

    Returns:
        list[str]: A list of filenames of the fetched clips.

    Raises:
        botocore.exceptions.ClientError: If a non-retryable error was encountered while interacting
        with AWS services. For example, missing permissions or invalid credentials.
        ValueError: If the start_timestamp is greater than the end_timestamp.

    Constants:
        KINESIS_VIDEO_SERVICE_NAME (str): The name of the AWS service for Kinesis Video.
        KINESIS_VIDEO_ARCHIVED_MEDIA_SERVICE_NAME (str): The name of the AWS service for Kinesis Video Archived Media.
        RETRYABLE_EXCEPTIONS (list of str): List of exceptions that are considered retryable.
        MAX_REQUESTS_PER_SECOND (int): Maximum number of requests per second allowed.
    """
    if start_timestamp >= end_timestamp:
        raise ValueError('start_timestamp must be less than end_timestamp!')
    if chunk_size <= timedelta(0):
        raise ValueError('chunk_size must be greater than 0!')

    kvs_client = session.client(service_name=Constants.KINESIS_VIDEO_SERVICE_NAME)

    # Obtain the GetClip endpoint using GetDataEndpoint
    get_data_endpoint_args = {
        'StreamName': stream_name,
        'APIName': 'GET_CLIP'
    }

    # Retry until successfully obtaining the endpoint
    endpoint = None
    retry_count = 0
    while True:
        try:
            get_data_endpoint_response = kvs_client.get_data_endpoint(**get_data_endpoint_args)

            if get_data_endpoint_response is not None and get_data_endpoint_response.get('DataEndpoint'):
                endpoint = get_data_endpoint_response['DataEndpoint']
                break
        except botocore.exceptions.ClientError as e:
            # Handle exceptions and retry if possible
            print(f"Error Code: {e.response['Error']['Code']}")
            print(f"Error Message: {e.response['Error']['Message']}")
            print(f"Request ID: {e.response['ResponseMetadata']['RequestId']}")
            print(f"Http status code: {e.response['ResponseMetadata']['HTTPStatusCode']}")

            if not e.response['Error']['Code'] in Constants.RETRYABLE_EXCEPTIONS:
                # Print and exit on non-retryable errors, such as InvalidArgumentException
                raise e

            if retry_count >= Constants.MAX_RETRIES:
                # Print and exit after hitting the maximum number of retries
                print("Maximum retries encountered!")
                raise e

        # Retry delay - Between 100ms and 5 seconds
        retry_count += 1
        print(f'Failed to get data endpoint. Trying again. Count: {retry_count}')
        time.sleep(random.uniform(0.1, 5))

    # Create the archived media client using the obtained endpoint
    kinesis_video_archived_media_client = session.client(
        service_name=Constants.KINESIS_VIDEO_ARCHIVED_MEDIA_SERVICE_NAME,
        endpoint_url=endpoint
    )

    get_clip_args = {
        'StreamName': stream_name,
        'ClipFragmentSelector': {
            'FragmentSelectorType': fragment_selector_type,
        },
    }
    output_file_names = []

    # Sliding window to call GetClip API
    # Window boundary is from chunk_start_timestamp to end_timestamp
    chunk_start_timestamp = start_timestamp
    while chunk_start_timestamp < end_timestamp:
        chunk_end_timestamp = min(chunk_start_timestamp + chunk_size, end_timestamp)
        get_clip_args['ClipFragmentSelector']['TimestampRange'] = {
            'StartTimestamp': chunk_start_timestamp,
            'EndTimestamp': chunk_end_timestamp
        }

        print(f'Fetching {chunk_start_timestamp} to {chunk_end_timestamp} for {stream_name}.')

        # Retry until successfully obtaining the chunk data
        # Or until it is determined that there is no media within this window
        chunk_data = None
        retry_count = 0
        while True:
            try:
                get_clip_response = kinesis_video_archived_media_client.get_clip(**get_clip_args)

                if get_clip_response is not None and get_clip_response['Payload'] is not None:
                    # Obtain the payload
                    chunk_data = get_clip_response['Payload'].read()

                    # GetClip returns the first 100 MB of the media.
                    # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_reader_GetClip.html
                    # Print a warning if we receive a chunk larger than 90 MB.
                    if len(chunk_data) > 90 * 1000 * 1000:
                        print('Warning: The clip may be be truncated. Reduce the chunk size!')

                    break
            except botocore.exceptions.ClientError as e:
                # Handle exceptions and retry if possible
                print(f"Error Code: {e.response['Error']['Code']}")
                print(f"Error Message: {e.response['Error']['Message']}")
                print(f"Request ID: {e.response['ResponseMetadata']['RequestId']}")
                print(f"Http status code: {e.response['ResponseMetadata']['HTTPStatusCode']}")

                if e.response['Error']['Code'] == 'ResourceNotFoundException':
                    # No media was found within this time range.
                    # Do nothing and advance the window.
                    print(f'No media found between {chunk_start_timestamp} to {chunk_end_timestamp}')
                    break

                if not e.response['Error']['Code'] in Constants.RETRYABLE_EXCEPTIONS:
                    # Print and exit on non-retryable errors, such as InvalidArgumentException
                    raise e

                if retry_count >= Constants.MAX_RETRIES:
                    # Print and exit after hitting the maximum retries
                    print("Maximum retries encountered!")
                    raise e

            # Retry delay - Between 100ms and 5 seconds
            retry_count += 1
            print(f'Retrying... count: {retry_count}')
            time.sleep(random.uniform(0.1, 5))

        if chunk_data is not None and len(chunk_data) > 0:
            # Write the payload to a file
            print(f'Received {len(chunk_data) / 1000 / 1000:0.2f} MB from Kinesis Video Streams')
            output_file_name = f'{stream_name}-{chunk_start_timestamp}-{chunk_end_timestamp}.mp4'
            with open(output_file_name, 'wb', encoding='utf-8') as f:
                f.write(chunk_data)

            output_file_names.append(output_file_name)

        # Advance the window
        chunk_start_timestamp = chunk_end_timestamp

        if chunk_start_timestamp == end_timestamp:
            return output_file_names

        time.sleep(1 / Constants.MAX_REQUESTS_PER_SECOND)


def check_ffmpeg() -> bool:
    """
    Check if FFmpeg is installed and accessible.

    This function attempts to run the 'ffmpeg -version' command to check if ffmpeg
    is installed and accessible from the system's PATH. If FFmpeg is found, it
    returns True. If FFmpeg is not found, it prints an error message and returns
    False.

    Returns:
        bool: True if FFmpeg is installed and accessible, False otherwise.
    """
    try:
        subprocess.run(['ffmpeg', '-version'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("Error: ffmpeg is not installed or not found. "
              "Install it and make sure it is added to your PATH environment variable.")
        return False


def merge_files(input_files: list[str], output_file: str, delete: bool = True) -> None:
    """
    Concatenate MP4 clips into a single MP4 file using FFMpeg.
    If there is only one input file, this function does nothing.

    Args:
        input_files (list of str): List of input file paths to be merged.
        output_file (str): Path to the output file.
        delete (bool, optional): If True, delete input files after merging. Defaults to True.
    """
    if len(input_files) == 1:
        return

    print(f'Merging {len(input_files)} files!')

    # One file per line. Each line is formatted: file '{filePath}'
    # https://trac.ffmpeg.org/wiki/Concatenate
    script_content = '\n'.join([f"file '{file}'" for file in input_files])
    script_filename = 'input_files_list.txt'
    with open(script_filename, 'w', encoding='utf-8') as script_file:
        script_file.write(script_content)

    # FFmpeg commend to concatenate the MP4 files
    ffmpeg_command = ['ffmpeg', '-f', 'concat', '-safe', '0', '-y', '-i', script_filename, '-c', 'copy', output_file]
    subprocess.run(ffmpeg_command)

    print(f'Finished merging {len(input_files)} files!')

    # Delete the script file
    os.remove(script_filename)

    # Optionally delete input files
    if delete:
        for file in input_files:
            os.remove(file)


def main(stream_name: str, start_time: str, end_time: str, tz_offset: float, fragment_selector_type: str,
         chunk_size_minutes: float):
    """
    Fetch media from a specified Kinesis Video stream between specified timestamps and saves it into a file.

    Args:
        stream_name (str): The name of the Kinesis video stream.
        start_timestamp (str): The start timestamp of the clip, in ISO 8601 format (timezone information is ignored).
        end_timestamp (str): The end timestamp of the clip, in ISO 8601 format (timezone information is ignored).
        tz_offset (float): The time zone offset in hours.
        fragment_selector_type (str): The origin of the timestamps to use (SERVER_TIMESTAMP or PRODUCER_TIMESTAMP).
        chunk_size_minutes (float): The length of chunks to fetch in minutes.
    """
    # Configure credentials.
    # Refer to https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html#configuring-credentials
    # for other authentication methods.
    session = boto3.Session()

    # The chunk size is the size of the clips that we request media using GetClip.
    # For example, if we want to download a 15-minute clip, it will be broken down into length of chunk_size.
    # Configure the chunk size based on the media size and fragment duration of your ingested media to fall
    # within the GetClip limits: https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/limits.html
    chunk_size = timedelta(minutes=chunk_size_minutes)

    # Configure timestamps to fetch the media.
    start_timestamp_utc = datetime.fromisoformat(start_time)
    end_timestamp_utc = datetime.fromisoformat(end_time)
    offset = timezone(timedelta(hours=tz_offset))
    start_timestamp = start_timestamp_utc.replace(tzinfo=offset)
    end_timestamp = end_timestamp_utc.replace(tzinfo=offset)

    # Check that FFMpeg is installed.
    if not check_ffmpeg():
        return

    output_files = fetch_clip(session, stream_name, start_timestamp, end_timestamp, chunk_size, fragment_selector_type)
    if len(output_files) == 0:
        print(f'No media found for {stream_name} between {start_timestamp} and {end_timestamp}')
    else:
        merge_files(output_files, f'{stream_name}-{start_timestamp}-{end_timestamp}.mp4', True)


if __name__ == "__main__":
    # Create the parser
    parser = argparse.ArgumentParser(description="Fetch long clips from an Amazon Kinesis video stream.")

    # Add the arguments
    parser.add_argument("--stream-name", "--stream-name", dest="stream_name", type=str, required=True,
                        help="Name of the Kinesis video stream to download.")
    parser.add_argument("--from", "--start", "--start-time", dest="start_time", type=str, required=True,
                        help="Start time in ISO 8601 format, in your timezone.")
    parser.add_argument("--to", "--end", "--end-time", dest="end_time", type=str, required=True,
                        help="End time in ISO 8601 format, in your timezone.")
    parser.add_argument("--tz-offset", "--tz", "--utc-offset", dest="offset_hours", type=float,
                        required=False, default=Constants.DEFAULT_TIMEZONE_OFFSET_HOURS,
                        help="Your timezone offset from UTC (in hours). Example: PST is -8. "
                             f"Default: {Constants.DEFAULT_TIMEZONE_OFFSET_HOURS}.")
    parser.add_argument("--selector-type", dest="selector_type", type=str, required=False,
                        choices=Constants.TIMESTAMP_SELECTOR_CHOICES,
                        default=Constants.DEFAULT_TIMESTAMP_SELECTOR_CHOICE,
                        help="The origin of the timestamps to use. "
                             f"Default: {Constants.DEFAULT_TIMESTAMP_SELECTOR_CHOICE}.")
    parser.add_argument("--chunk-size-minutes", dest="chunk_size_minutes", type=float, required=False,
                        default=Constants.DEFAULT_CHUNK_SIZE_MINUTES,
                        help=f"The chunk size in minutes. Default: {Constants.DEFAULT_CHUNK_SIZE_MINUTES}.")

    # Parse the arguments and pass them to main
    args = parser.parse_args()
    main(args.stream_name, args.start_time, args.end_time, args.offset_hours, args.selector_type,
         args.chunk_size_minutes)
