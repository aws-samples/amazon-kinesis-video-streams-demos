import React from 'react';
import {Button, Col, Container, Form, Row} from "react-bootstrap";

interface DataChannelInputProps {
    dataChannel: RTCDataChannel;
}

interface DataChannelInputState {
    sentMessageData: string;
    currentMessageContents: string;
}

class DataChannelInput extends React.Component<DataChannelInputProps, DataChannelInputState> {
    constructor(props: DataChannelInputProps) {
        super(props);

        this.state = {
            sentMessageData: '',
            currentMessageContents: '',
        }
    }

    onMessageBoxChanged = (event: React.ChangeEvent<HTMLInputElement>) => {
        this.setState({currentMessageContents: event.target.value});
    }

    onSendMessageButtonPressed = (event: React.MouseEvent<HTMLButtonElement>) => {
        const value = this.state.currentMessageContents;
        if (!value) {
            return;
        }
        try {
            this.props.dataChannel.send(this.state.currentMessageContents);
            this.setState({
                sentMessageData: this.state.sentMessageData + value + '\n',
                currentMessageContents: ''
            });
        } catch (e) {
            alert(e);
        }
    }

    render() {
        return (
            <Container fluid className="m-lg-auto">
                <Row>
                    <pre>{this.state.sentMessageData}</pre>
                </Row>
                <Row>
                    <Col>
                        <Form.Control type="text"
                                      placeholder="Data channel message to send"
                                      onChange={this.onMessageBoxChanged}
                                      value={this.state.currentMessageContents}/>
                    </Col>
                    <Col>
                        <Button variant="primary"
                                onClick={this.onSendMessageButtonPressed}>
                            Send data channel message
                        </Button>
                    </Col>
                </Row>
            </Container>
        )
    }
}

export default DataChannelInput;
