import argparse

from datetime import datetime, timezone, timedelta
from time import sleep
import re

import boto3
import botocore

# To avoid getting rate limited
MAX_REQUESTS_PER_SEC = 5
SLEEP_DURATION_MS = 1 / MAX_REQUESTS_PER_SEC

# Create a Kinesis Video client
client = boto3.client('kinesisvideo')


def delete_old_test_streams(stream_regex: str, dry_run: bool, time_threshold):
    stream_pattern = re.compile(stream_regex)

    print("Checking for old test streams...")
    next_token = None
    stream_count = 0
    streams_deleted = 0

    while True:
        if next_token:
            response = client.list_streams(NextToken=next_token, MaxResults=1000)
        else:
            response = client.list_streams(MaxResults=1000)

        stream_infos = response.get('StreamInfoList', [])

        for stream_info in stream_infos:
            stream_count += 1
            stream_name = stream_info.get('StreamName')
            stream_status = stream_info.get('Status')

            if not stream_status == 'ACTIVE':
                continue

            if stream_pattern.match(stream_name):
                creation_time = stream_info.get('CreationTime')

                if creation_time < time_threshold:
                    stream_arn = stream_info.get('StreamARN')
                    stream_version = stream_info.get('Version')

                    print(f"Deleting stream: {stream_arn} (created at {creation_time})"
                          f"{' (dry run)' if dry_run else ''}")

                    success = False
                    for attempt in range(5):
                        try:
                            if not dry_run:
                                client.delete_stream(
                                    StreamARN=stream_arn,
                                    CurrentVersion=stream_version
                                )
                            streams_deleted += 1
                            success = True
                            break
                        except botocore.exceptions.ClientError as e:
                            print(e)
                            if e.response['Error']['Code'] == 'ClientLimitExceededException':
                                # Try again when rate limited
                                sleep(SLEEP_DURATION_MS * (attempt + 1))
                                continue
                            raise e

                    # sleep(SLEEP_DURATION_MS)

                    if not success:
                        raise Exception(f'There was an issue deleting {stream_arn}!')

        next_token = response.get('NextToken')
        if not next_token:
            break

    return stream_count, streams_deleted


def delete_old_test_channels(channel_regex: str, dry_run: bool, time_threshold):
    channel_pattern = re.compile(channel_regex)

    print("Checking for old test channels...")
    next_token = None
    channel_count = 0
    channels_deleted = 0

    while True:
        if next_token:
            response = client.list_signaling_channels(NextToken=next_token, MaxResults=1000)
        else:
            response = client.list_signaling_channels(MaxResults=1000)

        channel_infos = response.get('ChannelInfoList', [])

        for channel_info in channel_infos:
            channel_count += 1
            channel_name = channel_info.get('ChannelName')
            channel_status = channel_info.get('ChannelStatus')

            if not channel_status == 'ACTIVE':
                continue

            if channel_pattern.match(channel_name):
                creation_time = channel_info.get('CreationTime')

                if creation_time < time_threshold:
                    channel_arn = channel_info.get('ChannelARN')
                    channel_version = channel_info.get('Version')

                    print(f"Deleting channel: {channel_arn} (created at {creation_time})"
                          f"{' (dry run)' if dry_run else ''}")

                    success = False
                    for attempt in range(5):
                        try:
                            if not dry_run:
                                client.delete_signaling_channel(
                                    ChannelARN=channel_arn,
                                    CurrentVersion=channel_version
                                )
                            channels_deleted += 1
                            success = True
                            break
                        except botocore.exceptions.ClientError as e:
                            print(e)
                            if e.response['Error']['Code'] == 'ClientLimitExceededException':
                                # Try again when rate limited
                                sleep(SLEEP_DURATION_MS * (attempt + 1))
                                continue
                            raise e

                    sleep(SLEEP_DURATION_MS)

                    if not success:
                        raise Exception(f'There was an issue deleting {channel_arn}!')

        next_token = response.get('NextToken')
        if not next_token:
            break

    return channel_count, channels_deleted


def main():
    parser = argparse.ArgumentParser(description="Clean up old Kinesis Video Streams and Signaling Channels")
    parser.add_argument('-d', '--dry-run', action='store_true',
                        help='List resources to delete but do not actually delete them')
    parser.add_argument('--stream-regex', default=r'^WrtcIngestionTestStream_[0-9a-fA-F]{32}$',
                        help='Regex pattern for stream names')
    parser.add_argument('--channel-regex', default=r'^ScaryTestChannel_[0-9a-zA-Z._-]{16}$',
                        help='Regex pattern for signaling channel names')
    parser.add_argument('--age-hours', type=int, default=24,
                        help='Delete resources older than this many hours (default: 24)')

    args = parser.parse_args()

    time_threshold = datetime.now(timezone.utc) - timedelta(hours=args.age_hours)

    print(f"Running with args: {args}")
    total, deleted = delete_old_test_streams(args.stream_regex, args.dry_run, time_threshold)
    print(f"Deleted {deleted} out of {total} streams")
    total, deleted = delete_old_test_channels(args.channel_regex, args.dry_run, time_threshold)
    print(f"Deleted {deleted} out of {total} signaling channels")
    print("Cleanup complete.")


if __name__ == "__main__":
    main()
