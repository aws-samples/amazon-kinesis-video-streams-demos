const aws4 = require('aws4')
const aws = require('aws-sdk')

const putMedia = function (videoFile, service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName) {
  console.log(dataEndpoint)
  const dataEndpointPutMedia = new aws.Endpoint(dataEndpoint)
  const url = dataEndpointPutMedia.href + 'putMedia'
  const options = aws4.sign(
    {
      method: 'POST',
      host: dataEndpointPutMedia.host,
      path: '/putMedia',
      service: service,
      region: region,
      headers: {
        Accept: '*/*',
        'x-amzn-fragment-timecode-type': 'RELATIVE',
        'x-amzn-producer-start-timestamp': Date.now() / 1000,
        'x-amzn-stream-name': streamName
      }
    },
    {
      accessKeyId: accessKeyID,
      secretAccessKey: secretAccessKey,
      sessionToken: sessionToken
    }
  )
  options.body = videoFile
  const request = new Request(url, options)
  fetch(request)
    .then(function (response) {
      return response.text()
    })
    .then(function (data) {
      console.log(data)
      if (data.includes('EventType":"ERROR"')) {
        alert('The following error has occurred:\n' + data + '\nReload the page and try again')
      }
    })
    .catch(function (error) {
      alert(error)
    })
}

const kinesisVideoObject = function (endpoint, region, accessKeyID, secretAccessKey, sessionToken) {
  const credentials = new aws.Credentials(accessKeyID, secretAccessKey, sessionToken)
  const options = {
    region: region,
    credentials: credentials,
    endpoint: endpoint
  }
  return new aws.KinesisVideo(options)
}

export { kinesisVideoObject, putMedia }
