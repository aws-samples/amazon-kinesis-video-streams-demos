/* eslint-disable object-shorthand */
const AWS = require('aws-sdk');
const AWS4 = require('aws4');

async function putMedia(videoFile, service, region, accessKeyID,
  secretAccessKey, sessionToken, dataEndpoint, streamName) {
  const dataEndpointPutMedia = new AWS.Endpoint(dataEndpoint);
  const url = `${dataEndpointPutMedia.href}putMedia`;

  const options = AWS4.sign(
    {
      method: 'POST',
      host: dataEndpointPutMedia.host,
      path: '/putMedia',
      service,
      region,
      headers: {
        Accept: '*/*',
        'x-amzn-fragment-timecode-type': 'RELATIVE',
        'x-amzn-producer-start-timestamp': Date.now() / 1000,
        'x-amzn-stream-name': streamName,
      },
    },
    {
      accessKeyId: accessKeyID,
      secretAccessKey: secretAccessKey,
      sessionToken: sessionToken,
    },
  );
  options.body = videoFile;
  const request = new Request(url, options);
  const response = await fetch(request);
  const text = await response.text();
  if (text.includes('"EventType":"ERROR"')) {
    throw new Error(text);
  }
}

module.exports = { putMedia };
