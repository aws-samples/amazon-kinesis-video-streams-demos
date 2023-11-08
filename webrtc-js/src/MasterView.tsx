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

const master: any = {
    peerConnectionByClientId: {},
    dataChannelByClientId: {},
};

function uid() {
    return Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
}

interface MasterViewProps {
    region: string;
    credentials: any;
    channelName: string;
    onStop: () => void;
}

interface MasterViewState {
    viewerVideoRef: React.RefObject<HTMLVideoElement>;
    masterVideoRef: React.RefObject<HTMLVideoElement>;
    dataChannelMessages: string;
    dataChannel: RTCDataChannel | null;
}

class MasterView extends React.Component<MasterViewProps, MasterViewState> {
    constructor(props: MasterViewProps) {
        super(props);

        this.state = {
            viewerVideoRef: React.createRef(),
            masterVideoRef: React.createRef(),
            dataChannelMessages: '',
            dataChannel: null,
        };
    }

    componentWillUnmount() {
        console.log('[VIEWER] Stopping viewer connection');
        if (master.signalingClient) {
            master.signalingClient.close();
            master.signalingClient = null;
        }

        if (master.peerConnection) {
            master.peerConnection.close();
            master.peerConnection = null;
        }

        if (master.localStream) {
            master.localStream.getTracks().forEach((track: MediaStreamTrack) => track.stop());
            master.localStream = null;
        }

        if (master.remoteView) {
            master.remoteView.srcObject = null;
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
            console.log('[MASTER] Channel ARN:', channelARN);

            // Get signaling channel endpoints
            const getSignalingChannelEndpointResponse: GetSignalingChannelEndpointOutput = await kinesisVideoClient.send(
                new GetSignalingChannelEndpointCommand({
                    ChannelARN: channelARN,
                    SingleMasterChannelEndpointConfiguration: {
                        Protocols: ['WSS', 'HTTPS'],
                        Role: 'MASTER',
                    },
                })
            );

            const endpointsByProtocol = getSignalingChannelEndpointResponse!.ResourceEndpointList!.reduce<Record<string, string>>((endpoints, endpoint: ResourceEndpointListItem) => {
                endpoints[endpoint!.Protocol!] = endpoint!.ResourceEndpoint!;
                return endpoints;
            }, {});
            console.log('[MASTER] Endpoints:', endpointsByProtocol);

            // Create KVS signaling client
            const kinesisVideoSignalingClient = new KinesisVideoSignalingClient({
                region: this.props.region,
                credentials: this.props.credentials,
                endpoint: endpointsByProtocol.HTTPS,
            } as KinesisVideoSignalingClientConfig);

            const iceServers: any[] = [];

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
            console.log('[MASTER] ICE servers:', iceServers);

            // Create Signaling Client
            master.signalingClient = new SignalingClient({
                channelARN,
                channelEndpoint: endpointsByProtocol.WSS,
                role: Role.MASTER,
                region: this.props.region,
                credentials: {
                    accessKeyId: this.props.credentials.accessKeyId,
                    secretAccessKey: this.props.credentials.secretAccessKey,
                    sessionToken: this.props.credentials.sessionToken,
                },
            });

            const resolution = {
                width: {ideal: 1280},
                height: {ideal: 720},
            };
            const constraints = {
                video: resolution,
                audio: true
            };

            master.localStream = await navigator.mediaDevices.getUserMedia(constraints);

            // @ts-ignore
            this.state.masterVideoRef.current.srcObject = master.localStream;

            master.signalingClient.on('open', async () => {
                console.log('[MASTER] Connected to signaling service. Waiting for viewers...');
            });

            master.signalingClient.on('sdpOffer', async (offer: any, remoteClientId: string) => {
                if (master.peerConnection) {
                    master.peerConnection.close();
                }

                const configuration = {
                    // @ts-ignore
                    iceServers,
                    iceTransportPolicy: 'all',
                } as RTCConfiguration;

                master.peerConnection = new RTCPeerConnection(configuration);
                const peerConnection = master.peerConnection;

                peerConnection.ondatachannel = (event: RTCDataChannelEvent) => {
                    this.setState({
                        dataChannel: event.channel,
                    });
                    event.channel.onmessage = (event: MessageEvent) => {
                        this.setState({
                            dataChannelMessages: this.state.dataChannelMessages + event.data + '\n',
                        });
                    };
                };

                peerConnection.addEventListener('connectionstatechange', async () => {
                    console.log('[MASTER] Connection state changed:', peerConnection.connectionState)
                });

                // Send any ICE candidates to the other peer
                // @ts-ignore
                peerConnection.addEventListener('icecandidate', ({candidate}) => {
                    if (candidate) {
                        console.log('[MASTER] Generated ICE candidate', candidate, 'for client', remoteClientId);
                        master.signalingClient.sendIceCandidate(candidate, remoteClientId);
                    } else {
                        console.log('[MASTER] All ICE candidates have been generated for client', remoteClientId);
                    }
                });

                // As remote tracks are received, add them to the remote view
                peerConnection.addEventListener('track', (event: TrackEvent) => {
                    console.log('[MASTER] Received remote track from client', remoteClientId);

                    // @ts-ignore
                    this.state.viewerVideoRef.current.srcObject = event.streams[0];
                });

                // If there's no video/audio, master.localStream will be null. So, we should skip adding the tracks from it.
                if (master.localStream) {
                    master.localStream.getTracks().forEach((track: MediaStreamTrack) => peerConnection.addTrack(track, master.localStream));
                }
                await peerConnection.setRemoteDescription(offer);

                // Create an SDP answer to send back to the client
                console.log('[MASTER] Creating SDP answer for client', remoteClientId);
                await peerConnection.setLocalDescription(
                    await peerConnection.createAnswer({
                        offerToReceiveAudio: true,
                        offerToReceiveVideo: true,
                    }),
                );

                console.log('[MASTER] Sending SDP answer to client', remoteClientId);
                const correlationId = uid();
                console.debug('SDP answer:', peerConnection.localDescription, 'correlationId:', correlationId);
                master.signalingClient.sendSdpAnswer(peerConnection.localDescription, remoteClientId, correlationId);
                console.log('[MASTER] Generating ICE candidates for client', remoteClientId);
            });

            master.signalingClient.on('iceCandidate', async (candidate: any, remoteClientId: string) => {
                console.log('[MASTER] Received ICE candidate from client', remoteClientId, candidate);

                // Add the ICE candidate received from the client to the peer connection
                const peerConnection = master.peerConnection;
                peerConnection.addIceCandidate(candidate);
            });

            master.signalingClient.on('statusResponse', (statusResponse: any) => {
                if (statusResponse.success) {
                    return;
                }
                console.error('[MASTER] Received response from Signaling:', statusResponse);
            });

            master.signalingClient.on('close', () => {
                console.log('[MASTER] Disconnected from signaling channel');
            });

            master.signalingClient.on('error', (error: any) => {
                console.error('[MASTER] Signaling client error', error);
            });

            console.log('[MASTER] Starting master connection');
            master.signalingClient.open();
        } catch (e) {
            alert('An error occurred. Check the debug console.')
            console.error(e);
        }
    }

    render() {
        return (
            <Container fluid className="m-lg-auto">
                <Row>
                    <Col>
                        <h2>Master (You)</h2>
                    </Col>
                    <Col>
                        <h2>From Viewer</h2>
                    </Col>
                </Row>
                <Row>
                    <Col>
                        <video id="master" ref={this.state.masterVideoRef}
                               style={{
                                   width: '100%',
                                   minHeight: '500px',
                                   maxHeight: '100px',
                                   position: 'relative'
                               }} autoPlay playsInline/>
                    </Col>
                    <Col>
                        <video id="from-viewer" ref={this.state.viewerVideoRef}
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
                            Stop Master
                        </Button>
                    </Col>
                </Row>
            </Container>
        )
    }
}

export default MasterView;
