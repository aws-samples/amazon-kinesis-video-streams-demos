import React from 'react';
import ViewerView from "./ViewerView";
import RegionSelector from "./RegionSelector";
import CredentialsProvider from "./CredentialsProvider";
import 'bootstrap/dist/css/bootstrap.min.css';
import {Button, Container} from "react-bootstrap";
import SignalingChannelInput from "./SignalingChannelInput";
import MasterView from "./MasterView";

enum View {
    Configuration, Master, Viewer
}

interface AppState {
    accessKey: string;
    secretKey: string;
    sessionToken: string;
    region: string;
    signalingChannel: string;
    currentView: View;
}

class App extends React.Component<{}, AppState> {
    constructor(props: {}) {
        super(props);

        this.state = {
            accessKey: localStorage.getItem('accessKey') || '',
            secretKey: localStorage.getItem('secretKey') || '',
            sessionToken: localStorage.getItem('sessionToken') || '',
            region: localStorage.getItem('region') || '',
            signalingChannel: localStorage.getItem('signalingChannel') || '',
            currentView: View.Configuration,
        }
    }

    isFormFilledIn = (): boolean => {
        return !!(this.state.accessKey &&
            this.state.secretKey &&
            this.state.region &&
            this.state.signalingChannel)
    }

    onUpdatedCredentials = (akid: string,
                            skid: string,
                            stid: string) => {
        this.setState((oldState) => {
            return {
                ...oldState,
                accessKey: akid,
                secretKey: skid,
                sessionToken: stid,
            };
        });
    }

    onUpdatedRegion = (region: string) => {
        this.setState((oldState) => {
            return {
                ...oldState,
                region: region
            };
        });
    }

    onUpdatedSignalingChannel = (signalingChannel: string) => {
        this.setState((oldState) => {
            return {
                ...oldState,
                signalingChannel: signalingChannel
            };
        });
    }

    masterButtonClicked = () => {
        this.setState({
            currentView: View.Master,
        });
    }

    viewerButtonClicked = () => {
        this.setState({
            currentView: View.Viewer,
        });
    }

    stopButtonClicked = () => {
        this.setState({
            currentView: View.Configuration,
        })
    }

    componentDidUpdate() {
        // Save to local storage.
        for (const [key, value] of Object.entries(this.state)) {
            if (typeof value !== 'string') {
                localStorage.setItem(key, JSON.stringify(value));
            } else {
                localStorage.setItem(key, value);
            }
        }
    }


    render() {
        switch (this.state.currentView) {
            case View.Configuration:
                const canStart: boolean = !this.isFormFilledIn();
                return (
                    <Container fluid className="m-lg-auto">
                        <h2>KVS WebRTC Test Page in React</h2>
                        <p>Use this page to connect to a signaling channel as a
                            VIEWER.</p>

                        <CredentialsProvider
                            credentialsChanged={this.onUpdatedCredentials}/>
                        <RegionSelector regionChanged={this.onUpdatedRegion}/>
                        <SignalingChannelInput
                            signalingChannelChanged={this.onUpdatedSignalingChannel}/>

                        <h2>Connect</h2>

                        <Button variant="primary" className="me-1"
                                disabled={canStart}
                                onClick={this.masterButtonClicked}>Start
                            Master</Button>
                        <Button variant="primary" disabled={canStart}
                                onClick={this.viewerButtonClicked}>Start
                            Viewer</Button>
                    </Container>
                );
            case View.Master:
                return (
                    <Container fluid className="m-lg-auto">
                        <MasterView region={this.state.region!}
                                    channelName={this.state.signalingChannel!}
                                    credentials={{
                                        accessKeyId: this.state.accessKey!,
                                        secretAccessKey: this.state.secretKey!,
                                        sessionToken: this.state.sessionToken!,
                                    }}
                                    onStop={this.stopButtonClicked}
                        />
                    </Container>
                )
            case View.Viewer:
                return (
                    <Container fluid className="m-lg-auto">
                        <ViewerView region={this.state.region!}
                                    channelName={this.state.signalingChannel!}
                                    credentials={{
                                        accessKeyId: this.state.accessKey!,
                                        secretAccessKey: this.state.secretKey!,
                                        sessionToken: this.state.sessionToken!,
                                    }}
                                    onStop={this.stopButtonClicked}
                        />
                    </Container>
                )
        }
    }
}

export default App;
