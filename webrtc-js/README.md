## Amazon Kinesis Video Streams WebRTC SDK in JS - Sample React implementation

This very minimal sample shows how to use the Amazon Kinesis Video Streams WebRTC
SDK in JS in the [**React framework**](https://react.dev/).

## Getting started

1. Clone the project.

```shell
git clone https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-js.git
```

2. Change directories to the one containing the `package.json`.
```shell
cd amazon-kinesis-video-streams-demos/webrtc-js
```

3. Install:

```shell
npm install
```

4. Start the React server. A browser window should open automatically (http://localhost:3000).

```shell
npm start
```

![Channel Configuration](./images/configuration-screen.png)

5. Fill in the fields and choose "Start Master". The button will be enabled once the fields 
are filled in. You can also use the other JS test page as Master if preferred: https://awslabs.github.io/amazon-kinesis-video-streams-webrtc-sdk-js/examples/index.html.

![Master View](./images/sample-peer-connection.png)

6. Open another tab of http://localhost:3000 or the [JS test page](https://awslabs.github.io/amazon-kinesis-video-streams-webrtc-sdk-js/examples/index.html). Connect the other peer to the Signaling Channel as Viewer.

![Master and Viewer View](./images/sample-peer-connection-labeled.png)

------

## App Layout

The App component has 3 different states it can be in: Configuration state, Master state, and Viewer state.

### Configuration state

![Channel Configuration](./images/ui-configuration-screen.png)

### Master state

![Channel Configuration](./images/ui-master-screen.png)

### Viewer state

![Channel Configuration](./images/ui-viewer-screen.png)

### Data channel component

![Channel Configuration](./images/ui-data-channel-component.png)
