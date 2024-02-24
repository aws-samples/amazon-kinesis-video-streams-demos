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
    # Recommended to use https://docs.aws.amazon.com/lambda/latest/dg/security-iam.html
    kvs_client = boto3.client(service_name=KINESIS_VIDEO_SERVICE_NAME, region_name=AWS_REGION)

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

            # Retry delay - Between 100ms and 5s
            print('Calling too fast... backing off', e)
            time.sleep(random.uniform(0.1, 5))

def lambda_handler(event, context):
    """
    Fetches and returns the total number of Kinesis Video Streams in an AWS account in a region.

    Args:
        event (dict): Unused. The event data passed to the Lambda function.
        context (LambdaContext): Unused. The runtime information of the Lambda function.

    Returns:
        dict: A dictionary containing the HTTP status code and response body.
            - If successful, returns HTTP status code 200 and the total number of Kinesis Video Streams.
            - If an error occurs, returns HTTP status code 500 and the error message.
    """
    try:
        streams = fetchNumberOfStreams()
        return {
            'statusCode': 200,
            'body': 'Total number of Kinesis Video Streams: {}'.format(streams)
        }
    except Exception as e:
        return {
            'statusCode': 500,
            'body': str(e)
        }
