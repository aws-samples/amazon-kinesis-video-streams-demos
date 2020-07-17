const kinesisVideo = function (accessKeyID, secretAccessKey, sessionToken, region, endpoint) {
    // eslint-disable-next-line no-undef
    const credentials = new AWS.Credentials(accessKeyID, secretAccessKey, sessionToken)
    const options = {
      region: region,
      credentials: credentials,
      endpoint: endpoint
    }
    // eslint-disable-next-line no-undef
    return new AWS.KinesisVideo(options)
    }

  export { kinesisVideo }