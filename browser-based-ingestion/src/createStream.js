const createStream = async function (kinesisvideo, params) {
  const request = kinesisvideo.createStream(params)
  return request.promise()
}

export { createStream }
