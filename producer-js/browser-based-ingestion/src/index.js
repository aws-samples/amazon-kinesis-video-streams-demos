/* eslint-disable no-alert */
const AWS = require('aws-sdk');

const { getBlobFromWebcam, getBlobFromFile, stopRecording } = require('./getProcessedVideo');

const startRecordButton = document.getElementById('startRecord');
const stopRecordButton = document.getElementById('stopRecord');
const webcam = document.getElementById('webcam');
const fileUploadRequirements = document.getElementById('fileUploadRequirements');
const videoRecordRequirements = document.getElementById('videoRecordRequirements');
const regionElement = document.getElementById('region');
const serviceElement = document.getElementById('service');
const accessKeyIDElement = document.getElementById('accessKeyID');
const secretAccessKeyElement = document.getElementById('secretAccessKey');
const sessionTokenElement = document.getElementById('sessionToken');
const streamNameElement = document.getElementById('streamName');
const endpointElement = document.getElementById('endpoint');
const audioElement = document.getElementById('audio');
const paramsElement = document.getElementById('streamParams');
const heightElement = document.getElementById('height');
const widthElement = document.getElementById('width');
const latencyElement = document.getElementById('latency');
const frameRateElement = document.getElementById('frameRate');
const inputFileElement = document.getElementById('inputFile');
const containerSelectionElement = document.getElementsByName('containerSelection');
const streamSelectionElement = document.getElementsByName('streamSelection');
const dataFeedSelectionElement = document.getElementsByName('dataFeedSelection');
const fileUpload = dataFeedSelectionElement[0];
const recordWithWebcam = dataFeedSelectionElement[1];

function fileUploadDisplay() {
  stopRecordButton.classList.add('hide');
  startRecordButton.value = 'Send video-file';
  fileUploadRequirements.classList.remove('hide');
  videoRecordRequirements.classList.add('hide');
}

function recordWithWebcamDisplay() {
  startRecordButton.value = 'Start recording';
  fileUploadRequirements.classList.add('hide');
  videoRecordRequirements.classList.remove('hide');
  stopRecordButton.classList.remove('hide');
}

function kinesisVideo(accessKeyID, secretAccessKey, sessionToken, region, endpoint) {
  const credentials = new AWS.Credentials(accessKeyID, secretAccessKey, sessionToken);
  const options = {
    region,
    credentials,
    endpoint,
  };
  return new AWS.KinesisVideo(options);
}

function getDataEndpoint(kinesisvideo, streamName) {
  const params = {
    APIName: 'PUT_MEDIA',
    StreamName: streamName,
  };
  const request = kinesisvideo.getDataEndpoint(params);
  return request.promise();
}

function createStream(kinesisvideo, params) {
  const request = kinesisvideo.createStream(params);
  return request.promise();
}

function getHeight() {
  const height = heightElement.value;
  return (height !== '' && height > 0 && height < 720) ? height : 480;
}

function getWidth() {
  const width = widthElement.value;
  return (width !== '' && width > 0 && width < 1080) ? width : 640;
}

function getLatency() {
  const latency = latencyElement.value;
  return (latency !== '' && latency > 1000) ? latency : 2000;
}

function getFrameRate() {
  const frameRate = frameRateElement.value;
  return (frameRate !== '' && frameRate >= 30) ? frameRate : 30;
}

function stopProcess() {
  stopRecording();
  startRecordButton.disabled = false;
  stopRecordButton.disabled = true;
}

async function init(newStream, recordVideo, endpoint, region, service, accessKeyID,
  secretAccessKey, sessionToken, streamName, params,
  audio, height, width, frameRate, latency) {
  startRecordButton.disabled = true;
  stopRecordButton.disabled = false;
  const kinesisvideo = kinesisVideo(accessKeyID, secretAccessKey, sessionToken, region, endpoint);
  if (newStream) {
    try {
      await createStream(kinesisvideo, JSON.parse(params));
    } catch (error) {
      alert(error);
      stopProcess();
      return;
    }
  }
  try {
    const dataEndpoint = await getDataEndpoint(kinesisvideo, streamName);
    if (recordVideo) {
      if ('DataEndpoint' in dataEndpoint) {
        getBlobFromWebcam(service, region, accessKeyID, secretAccessKey,
          sessionToken, dataEndpoint.DataEndpoint,
          streamName, audio, height, width, frameRate, latency, webcam);
      } else {
        throw new Error('DataEndpoint not found, please try again with a valid stream');
      }
    } else {
      const inputFile = inputFileElement.files[0];
      const inputFiletype = !containerSelectionElement[1].checked;
      await getBlobFromFile(service, region, accessKeyID, secretAccessKey,
        sessionToken, dataEndpoint.DataEndpoint, streamName, inputFile, inputFiletype);
      startRecordButton.disabled = false;
    }
  } catch (error) {
    alert(error);
    stopProcess();
  }
}

async function startProcess() {
  const region = regionElement.value.trim();
  const service = serviceElement.value.trim();
  const accessKeyID = accessKeyIDElement.value;
  const secretAccessKey = secretAccessKeyElement.value;
  const sessionToken = sessionTokenElement.value;
  const streamName = streamNameElement.value.trim();
  const endpoint = endpointElement.value.trim();

  if (!region || !service || !accessKeyID || !secretAccessKey || !streamName) {
    alert('Access Key ID/Secret Access Key/Session Token/Service/Region/Stream Name is missing!\nPlease provide the mandatory information to proceed');
    return;
  }

  const newStream = streamSelectionElement[0].checked;
  const recordVideo = recordWithWebcam.checked;
  const params = paramsElement.value;
  const audio = audioElement.checked;

  init(newStream, recordVideo, endpoint, region, service,
    accessKeyID, secretAccessKey, sessionToken,
    streamName, params, audio, getHeight(), getWidth(), getFrameRate(), getLatency());
}

fileUpload.onchange = fileUploadDisplay;
recordWithWebcam.onchange = recordWithWebcamDisplay;

startRecordButton.onclick = startProcess;
stopRecordButton.onclick = stopProcess;
