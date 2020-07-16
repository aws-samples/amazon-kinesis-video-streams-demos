const getDataEndpoint = async function (kinesisvideo, streamName) {
  const params = {
    APIName: 'PUT_MEDIA',
    StreamName: streamName
  }
  const request = kinesisvideo.getDataEndpoint(params)
  return request.promise()
}

export { getDataEndpoint }
