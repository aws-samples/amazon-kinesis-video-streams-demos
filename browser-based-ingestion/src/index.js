import { kinesisVideoObject } from './AWS-SDKCalls.js'
import { createStream } from './createStream.js'
import { getDataEndpoint } from './getDataEndpoint.js'
import { getBlobFromWebcam, getBlobFromFile, stopRecording } from './getProcessedVideo.js'

var startRecordButton = document.getElementById('startRecord')
var stopRecordButton = document.getElementById('stopRecord')
const webcam = document.getElementById('webcam')
var fileUpload = document.getElementsByName('dataFeedSelection')[0]
var recordWithWebcam = document.getElementsByName('dataFeedSelection')[1]
var fileUploadRequirements = document.getElementById('fileUploadRequirements')
var videoRecordRequirements = document.getElementById('videoRecordRequirements')

fileUpload.onchange = function () {
  stopRecordButton.classList.add('hide')
  startRecordButton.value = 'Send Data to PutMedia'
  fileUploadRequirements.classList.remove('hide')
  videoRecordRequirements.classList.add('hide')
}

recordWithWebcam.onchange = function () {
  startRecordButton.value = 'Start recording'
  fileUploadRequirements.classList.add('hide')
  videoRecordRequirements.classList.remove('hide')
  stopRecordButton.classList.remove('hide')
}

const startProcess = async function () {
  const region = document.getElementById('region').value
  const service = document.getElementById('service').value
  const accessKeyID = document.getElementById('accessKeyID').value
  const secretAccessKey = document.getElementById('secretAccessKey').value
  const sessionToken = document.getElementById('sessionToken').value
  const streamName = document.getElementById('streamName').value.trim()
  const endpoint = 'https://beta.us-west-2.acuity.amazonaws.com/'

  if (!region || !service || !accessKeyID || !secretAccessKey || !streamName) {
    alert('Access Key ID/Secret Access Key/Session Token/Service/Region/Stream Name is missing!\nPlease provide the mandatory information to proceed')
    return
  }

  const newStream = !!document.getElementsByName('streamSelection')[0].checked
  const recordVideo = !!document.getElementsByName('dataFeedSelection')[1].checked
  const params = document.getElementById('streamParams').value

  const audio = document.getElementById('audio').checked
  init(newStream, recordVideo, endpoint, region, service, accessKeyID, secretAccessKey, sessionToken,
    streamName, webcam, params, audio, getHeight(), getWidth(), getFrameRate(), getLatency())
}

function getHeight () {
  const height = document.getElementById('height').value
  return (height !== '' && height > 0 && height < 720) ? height : 480
}

function getWidth () {
  const width = document.getElementById('width').value
  return (width !== '' && width > 0 && width < 1080) ? width : 640
}

function getLatency () {
  const latency = document.getElementById('latency').value
  return (latency !== '' && latency > 1000) ? latency : 2000
}

function getFrameRate () {
  const frameRate = document.getElementById('frameRate').value
  return (frameRate !== '' && frameRate >= 30) ? frameRate : 30
}

const init = async function (newStream, recordVideo, endpoint, region, service, accessKeyID,
  secretAccessKey, sessionToken, streamName, webcam, params,
  audio, height, width, frameRate, latency) {
  startRecordButton.disabled = true
  stopRecordButton.disabled = false
  const kinesisvideo = kinesisVideoObject(endpoint, region, accessKeyID, secretAccessKey, sessionToken)
  if (newStream) {
    try {
      await createStream(kinesisvideo, JSON.parse(params))
    } catch (error) {
      alert(error)
      startRecordButton.disabled = false
      stopRecordButton.disabled = true
      return
    }
  }
  try {
    const dataEndpoint = await getDataEndpoint(kinesisvideo, streamName)
    if (recordVideo) {
      // eslint-disable-next-line no-prototype-builtins
      if (dataEndpoint.hasOwnProperty('DataEndpoint')) {
        getBlobFromWebcam(service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint.DataEndpoint,
          streamName, audio, height, width, frameRate, latency, webcam)
      }
    } else {
      const inputFile = document.getElementById('inputFile').files[0]
      const inputFiletype = !document.getElementsByName('containerSelection')[1].checked
      await getBlobFromFile(service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint.DataEndpoint, streamName, inputFile, inputFiletype)
      startRecordButton.disabled = false
    }
  } catch (error) {
    alert(error)
    startRecordButton.disabled = false
    stopRecordButton.disabled = true
  }
}

function stopProcess () {
  stopRecording()
  startRecordButton.disabled = false
  stopRecordButton.disabled = true
}

startRecordButton.onclick = startProcess
stopRecordButton.onclick = stopProcess
