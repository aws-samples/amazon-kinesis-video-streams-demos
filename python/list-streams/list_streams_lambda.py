import boto3
import time
import random
import botocore.exceptions

retryable_exceptions = ['ClientLimitExceededException', 'InternalFailure']
max_requests_per_second = 20

def fetchNumberOfStreams():
    kvs_client = boto3.client(service_name='kinesisvideo', region_name='us-west-2')

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

            # Delay between API calls
            time.sleep(1 / max_requests_per_second)

        except botocore.exceptions.ClientError as e:
            print('Error Code: {}'.format(e.response['Error']['Code']))
            print('Error Message: {}'.format(e.response['Error']['Message']))
            print('Request ID: {}'.format(e.response['ResponseMetadata']['RequestId']))
            print('Http code: {}'.format(e.response['ResponseMetadata']['HTTPStatusCode']))

            if not e.response['Error']['Code'] in retryable_exceptions:
                # Print and exit on non-retryable errors, such as InvalidArgumentException
                raise e

            # Retry delay - Between 100ms and 5s
            print('Calling too fast... backing off', e)
            time.sleep(random.uniform(0.1, 5))

def lambda_handler(event, context):
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