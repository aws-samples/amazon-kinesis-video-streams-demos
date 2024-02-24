import boto3
import time
import random
import botocore.exceptions

# https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_ListStreams.html#API_ListStreams_Errors
RETRYABLE_EXCEPTIONS = ['ClientLimitExceededException', 'InternalFailure']
# https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/limits.html
MAX_REQUESTS_PER_SECOND = 20

KINESIS_VIDEO_SERVICE_NAME = 'kinesisvideo'
AWS_REGION = 'us-west-2'

def fetchNumberOfStreams():
    """
    Fetches the total number of Kinesis video streams in an AWS account in a certain region.

    Returns:
        int: The total number of Kinesis video streams in an AWS account in a certain region.

    Raises:
        botocore.exceptions.ClientError: A non-retryable error occurred when making requests to the AWS service.
            This could include errors such as authorization or permission issues.

    Constants:
        KINESIS_VIDEO_SERVICE_NAME (str): The name of the AWS service for Kinesis Video.
        AWS_REGION (str): The AWS region where the streams are located.
        RETRYABLE_EXCEPTIONS (list of str): List of exceptions that are considered retryable.
        MAX_REQUESTS_PER_SECOND (int): Maximum number of requests per second allowed.
    """
    # Refer to https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html#configuring-credentials
    # for other authentication methods.
    kvs_client = boto3.client(service_name=KINESIS_VIDEO_SERVICE_NAME,
                              region_name=AWS_REGION,
                              aws_access_key_id='x',
                              aws_secret_access_key='x')

    streams_count = 0
    list_streams_args = {'MaxResults': 1000}
    while streams_count == 0 or 'NextToken' in list_streams_args:
        try:
            list_streams_response = kvs_client.list_streams(**list_streams_args)
            if list_streams_response is not None:
                streams = len(list_streams_response['StreamInfoList'])
                streams_count += streams
                print('Added {} - Total: {}'.format(streams, streams_count))
                if 'NextToken' in list_streams_response:
                    list_streams_args['NextToken'] = list_streams_response['NextToken']
                else:
                    return streams_count

            # Delay between API calls - to prevent getting throttled
            # https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/limits.html
            time.sleep(1 / MAX_REQUESTS_PER_SECOND)

        except botocore.exceptions.ClientError as e:
            print('Error Code: {}'.format(e.response['Error']['Code']))
            print('Error Message: {}'.format(e.response['Error']['Message']))
            print('Request ID: {}'.format(e.response['ResponseMetadata']['RequestId']))
            print('Http status code: {}'.format(e.response['ResponseMetadata']['HTTPStatusCode']))

            if not e.response['Error']['Code'] in RETRYABLE_EXCEPTIONS:
                # Print and exit on non-retryable errors, such as InvalidArgumentException
                raise e

            # Retry delay - Between 100ms and 5 seconds
            print('Calling too fast... backing off', e)
            time.sleep(random.uniform(0.1, 5))

def main():
    """
    Fetches and prints the total number of Kinesis Video Streams in an AWS account in a region.

    Raises:
        Exception: If a non-retryable error occurs during execution.
    """
    print(f'There are {fetchNumberOfStreams()} streams!')

if __name__ == '__main__':
    main()
