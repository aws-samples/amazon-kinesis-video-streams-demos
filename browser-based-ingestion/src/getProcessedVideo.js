import { putMedia } from './AWS-SDKCalls.js'
// eslint-disable-next-line no-undef
const { createFFmpeg } = FFmpeg
const ffmpeg = createFFmpeg({ log: true })

var recording = false

const transformVideo = async function (videoUint8Array, streamName, h264) {
  const currrentTime = Date.now()
  await ffmpeg.write('record', videoUint8Array)
  if (h264) {
    await ffmpeg.run('-i record -c:v copy -acodec aac -threads 8 ' + streamName + '-' + currrentTime + '.mkv')
  } else {
    await ffmpeg.run('-i record -vcodec h264 -acodec aac -threads 8 ' + streamName + '-' + currrentTime + '.mkv')
  }
  return await ffmpeg.read(streamName + '-' + currrentTime + '.mkv') // uint8array
}

const sleep = async function (ms) {
  return new Promise(function (resolve) {
    setTimeout(resolve, ms)
  })
}

const getBlobFromFile = async function (service, region, accessKeyID, secretAccessKey, sessionToken,
  dataEndpoint, streamName, inputFile, h264) {
  await ffmpeg.load()
  const reader = new FileReader()
  reader.onload = async function (e) {
    const uint8Array = new Uint8Array(e.target.result)
    const videoFile = await transformVideo(uint8Array, streamName, h264)
    putMedia(videoFile, service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName)
  }
  reader.readAsArrayBuffer(inputFile)
}

const getBlobFromWebcam = async function (service, region, accessKeyID, secretAccessKey, sessionToken,
  dataEndpoint, streamName, audio, height, width, frameRate, latency,
  webcam = null) {
  await ffmpeg.load()
  var stream = await navigator.mediaDevices.getUserMedia({
    audio: audio,
    video: true,
    width: width,
    height: height,
    frameRate: frameRate
  })

  if (webcam) {
    webcam.srcObject = stream
    await webcam.play()
  }

  // eslint-disable-next-line no-undef
  const recorder = RecordRTC(stream, {
    type: 'video/x-matroska;codecs=avc1'
  })
  recorder.startRecording()
  recording = true

  async function getAndSendFrame () {
    recorder.stopRecording(async function () {
      const uint8Array = new Uint8Array(await recorder.getBlob().arrayBuffer())
      if (uint8Array.length > 0) {
        try {
          const videoFile = await transformVideo(uint8Array, streamName, true)
          putMedia(videoFile, service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName)
        } catch (error) {
          alert('Some eror occurred, please reload the page and try again')
        }
      }
    })

    recorder.startRecording()
  }
  // eslint-disable-next-line no-unmodified-loop-condition
  while (recording) {
    await getAndSendFrame()
    await sleep(latency)
  }
}

const stopRecording = function stopRecording () {
  recording = false
}

export { getBlobFromWebcam, getBlobFromFile, transformVideo, stopRecording }
