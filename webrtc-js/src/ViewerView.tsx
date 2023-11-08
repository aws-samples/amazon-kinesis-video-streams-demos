import React from 'react';

import {
    DescribeSignalingChannelCommand,
    DescribeSignalingChannelOutput,
    GetSignalingChannelEndpointCommand,
    GetSignalingChannelEndpointOutput,
    KinesisVideoClient,
    KinesisVideoClientConfig,
    ResourceEndpointListItem
} from "@aws-sdk/client-kinesis-video";
import {
    GetIceServerConfigCommand,
    GetIceServerConfigCommandOutput,
    KinesisVideoSignalingClient,
    KinesisVideoSignalingClientConfig
} from "@aws-sdk/client-kinesis-video-signaling";
import {Role, SignalingClient} from "amazon-kinesis-video-streams-webrtc";
import {Button, Col, Container, Row} from "react-bootstrap";
import DataChannelInput from "./DataChannelInput";

const viewer: any = {};

function uid() {
    return Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
}

interface LiveFeedViewProps {
    region: string;
    credentials: { accessKeyId: string, secretAccessKey: string, sessionToken: string | undefined };
    channelName: string;
    onStop: () => void;
}

interface LiveFeedViewState {
    masterVideoRef: React.RefObject<HTMLVideoElement>;
    viewerVideoRef: React.RefObject<HTMLVideoElement>;
    dataChannelMessages: string;
    dataChannel: RTCDataChannel | null;
}

class ViewerView extends React.Component<LiveFeedViewProps, LiveFeedViewState> {
    constructor(props: LiveFeedViewProps) {
        super(props);

        this.state = {
            masterVideoRef: React.createRef(),
            viewerVideoRef: React.createRef(),
            dataChannelMessages: '',
            dataChannel: null,
        };
    }

    componentWillUnmount() {
        console.log('[VIEWER] Stopping viewer connection');
        if (viewer.signalingClient) {
            viewer.signalingClient.close();
            viewer.signalingClient = null;
        }

        if (viewer.peerConnection) {
            viewer.peerConnection.close();
            viewer.peerConnection = null;
        }

        if (viewer.localStream) {
            viewer.localStream.getTracks().forEach((track: MediaStreamTrack) => track.stop());
            viewer.localStream = null;
        }

        if (viewer.remoteStream) {
            viewer.remoteStream.getTracks().forEach((track: MediaStreamTrack) => track.stop());
            viewer.remoteStream = null;
        }

        if (viewer.dataChannel) {
            viewer.dataChannel = null;
        }
    }

    async componentDidMount() {
        try {
            // Create KVS client
            const kinesisVideoClient = new KinesisVideoClient({
                region: this.props.region,
                credentials: this.props.credentials,
            } as KinesisVideoClientConfig);

            // Get signaling channel ARN
            const describeSignalingChannelResponse: DescribeSignalingChannelOutput = await kinesisVideoClient.send(
                new DescribeSignalingChannelCommand({ChannelName: this.props.channelName}) as DescribeSignalingChannelCommand
            );

            const channelARN: string = describeSignalingChannelResponse!.ChannelInfo!.ChannelARN!;
            console.log('[VIEWER] Channel ARN:', channelARN);

            // Get signaling channel endpoints
            const getSignalingChannelEndpointResponse: GetSignalingChannelEndpointOutput = await kinesisVideoClient.send(
                new GetSignalingChannelEndpointCommand({
                    ChannelARN: channelARN,
                    SingleMasterChannelEndpointConfiguration: {
                        Protocols: ['WSS', 'HTTPS'],
                        Role: 'VIEWER',
                    },
                })
            );

            const endpointsByProtocol = getSignalingChannelEndpointResponse!.ResourceEndpointList!.reduce<Record<string, string>>((endpoints, endpoint: ResourceEndpointListItem) => {
                endpoints[endpoint!.Protocol!] = endpoint!.ResourceEndpoint!;
                return endpoints;
            }, {});
            console.log('[VIEWER] Endpoints:', endpointsByProtocol);

            // Create KVS signaling client
            const kinesisVideoSignalingClient = new KinesisVideoSignalingClient({
                region: this.props.region,
                credentials: this.props.credentials,
                endpoint: endpointsByProtocol.HTTPS,
            } as KinesisVideoSignalingClientConfig);

            const iceServers = [];

            // Add STUN server
            iceServers.push({urls: `stun:stun.kinesisvideo.${this.props.region}.amazonaws.com:443`});

            // Add TURN servers
            // Get ICE server configuration
            const getIceServerConfigResponse: GetIceServerConfigCommandOutput = await kinesisVideoSignalingClient.send(
                new GetIceServerConfigCommand({
                    ChannelARN: channelARN,
                })
            );
            console.log(getIceServerConfigResponse);
            getIceServerConfigResponse!.IceServerList!.forEach(iceServer =>
                iceServers.push({
                    urls: iceServer.Uris,
                    username: iceServer.Username,
                    credential: iceServer.Password,
                }),
            );
            console.log('[VIEWER] ICE servers:', iceServers);

            // Create Signaling Client
            viewer.signalingClient = new SignalingClient({
                channelARN,
                channelEndpoint: endpointsByProtocol.WSS,
                clientId: uid(),
                role: Role.VIEWER,
                region: this.props.region,
                credentials: {
                    accessKeyId: this.props.credentials.accessKeyId,
                    secretAccessKey: this.props.credentials.secretAccessKey,
                    sessionToken: this.props.credentials.sessionToken,
                },
            });

            const configuration = {
                iceServers,
                iceTransportPolicy: 'all',
            } as RTCConfiguration;

            viewer.peerConnection = new RTCPeerConnection(configuration);
            const dataChannelObj = viewer.peerConnection.createDataChannel('kvsDataChannel');
            dataChannelObj.onopen = (event: any) => {
                dataChannelObj.send("Opened data channel by viewer");
                this.setState({dataChannel: dataChannelObj});
            };

            const updateState = (event: MessageEvent) => {
                if (!event.data) {
                    return;
                }
                this.setState({dataChannelMessages: this.state.dataChannelMessages + event.data + '\n'});
            }
            // Callback for the data channel created by viewer
            dataChannelObj.onmessage = updateState;

            viewer.peerConnection.ondatachannel = (event: RTCDataChannelEvent) => {
                // Callback for the data channel created by master
                event.channel.onmessage = updateState;
            };

            viewer.signalingClient.on('open', async () => {
                console.log('[VIEWER] Connected to signaling service');

                const resolution = {
                    width: {ideal: 1280},
                    height: {ideal: 720},
                };
                const constraints = {
                    video: resolution,
                    audio: true
                };

                viewer.localStream = await navigator.mediaDevices.getUserMedia(constraints);
                viewer.localStream.getTracks().forEach((track: MediaStreamTrack) => viewer.peerConnection.addTrack(track, viewer.localStream));
                // @ts-ignore
                this.state.viewerVideoRef.current.srcObject = viewer.localStream;

                // Create an SDP offer to send to the master
                console.log('[VIEWER] Creating SDP offer');
                await viewer.peerConnection.setLocalDescription(
                    await viewer.peerConnection.createOffer({
                        offerToReceiveAudio: true,
                        offerToReceiveVideo: true,
                    }),
                );

                // When trickle ICE is enabled, send the offer now and then send ICE candidates as they are generated. Otherwise wait on the ICE candidates.
                console.log('[VIEWER] Sending SDP offer');
                viewer.signalingClient.sendSdpOffer(viewer.peerConnection.localDescription);
                console.log('[VIEWER] Generating ICE candidates');
            });

            viewer.signalingClient.on('sdpAnswer', async (answer: any) => {
                // Add the SDP answer to the peer connection
                console.log('[VIEWER] Received SDP answer');
                await viewer.peerConnection.setRemoteDescription(answer);
            });

            viewer.signalingClient.on('iceCandidate', (candidate: any) => {
                // Add the ICE candidate received from the MASTER to the peer connection
                console.log('[VIEWER] Received ICE candidate');
                viewer.peerConnection.addIceCandidate(candidate);
            });

            viewer.signalingClient.on('close', () => {
                console.log('[VIEWER] Disconnected from signaling channel');
            });

            viewer.signalingClient.on('error', (error: any) => {
                console.error('[VIEWER] Signaling client error: ', error);
            });

            console.log('[VIEWER] Starting viewer connection');
            viewer.signalingClient.open();

            // Send any ICE candidates to the other peer
            viewer.peerConnection.addEventListener('icecandidate', ({candidate}: any) => {
                if (candidate) {
                    console.log('[VIEWER] Generated ICE candidate', candidate);
                    // When trickle ICE is enabled, send the ICE candidates as they are generated.
                    console.log('[VIEWER] Sending ICE candidate');
                    viewer.signalingClient.sendIceCandidate(candidate);
                } else {
                    console.log('[VIEWER] All ICE candidates have been generated');
                }
            });

            // As remote tracks are received, add them to the remote view
            viewer.peerConnection.addEventListener('track', async (event: RTCTrackEvent) => {
                console.log('[VIEWER] Received remote track');

                viewer.remoteStream = event.streams[0];

                // @ts-ignore
                this.state.masterVideoRef.current.srcObject = event.streams[0];
            });
        } catch (e) {
            alert('An error occurred. Check the debug console.')
            console.error(e);
        }
    }

    render() {
        return (
            <Container>
                <Row>
                    <Col>
                        <h2>Viewer (You)</h2>
                    </Col>
                    <Col>
                        <h2>From Master</h2>
                    </Col>
                </Row>
                <Row>
                    <Col>
                        <video id="viewer" ref={this.state.viewerVideoRef}
                               style={{
                                   width: '100%',
                                   minHeight: '500px',
                                   maxHeight: '100px',
                                   position: 'relative'
                               }} autoPlay playsInline/>
                    </Col>
                    <Col>
                        <video id="from-master" ref={this.state.masterVideoRef}
                               style={{
                                   width: '100%',
                                   minHeight: '500px',
                                   maxHeight: '100px',
                                   position: 'relative'
                               }} autoPlay playsInline/>
                    </Col>
                </Row>
                <Row>
                    <Col>
                        {this.state.dataChannel?.readyState === 'open' ?
                            <DataChannelInput
                                dataChannel={this.state.dataChannel}></DataChannelInput> : null}
                    </Col>
                    <Col>
                        <pre className="text-body-secondary">
                            {this.state.dataChannelMessages}
                        </pre>
                    </Col>
                </Row>
                <br/>
                <Row>
                    <Col>
                        <Button variant="danger"
                                onClick={this.props.onStop}>
                            Stop Viewer
                        </Button>
                    </Col>
                </Row>
            </Container>
        )
    }
}

export default ViewerView;
