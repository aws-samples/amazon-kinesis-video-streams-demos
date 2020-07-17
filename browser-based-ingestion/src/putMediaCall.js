import { AwsV4Signer } from '../lib/aws4fetch.esm.js'
const putMedia = async function (videoFile, service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName) {
  // eslint-disable-next-line no-undef
  const dataEndpointPutMedia = new AWS.Endpoint(dataEndpoint)
  const url = dataEndpointPutMedia.href + 'putMedia'

  const headers = {
    Accept: '*/*',
    'x-amzn-fragment-timecode-type': 'RELATIVE',
    'x-amzn-producer-start-timestamp': Date.now() / 1000,
    'x-amzn-stream-name': streamName,
    'x-amz-date': (new Date()).toISOString().replace(/-|:|\.\d+/g, '')
  }
  const signer = new AwsV4Signer({
    url: url,
    accessKeyId: accessKeyID,
    secretAccessKey: secretAccessKey,
    sessionToken: sessionToken,
    method: 'POST',
    headers: headers,
    service: service,
    region: region
  })

  // eslint-disable-next-line no-undef
  headers.Authorization = await signer.authHeader()
  const options =
    {
      method: 'POST',
      host: dataEndpointPutMedia.host,
      path: '/putMedia',
      service: service,
      region: region,
      headers: headers,
      body: videoFile
    }
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

export { putMedia }
