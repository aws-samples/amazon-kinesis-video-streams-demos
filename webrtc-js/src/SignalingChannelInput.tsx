import React from 'react';
import {Col, Form, Row} from 'react-bootstrap';

interface SignalingChannelInputProps {
    signalingChannelChanged: (updatedSignalingChannel: string) => void;
}

interface SignalingChannelInputState {
    signalingChannel: string;
}

class SignalingChannelInput extends React.Component<SignalingChannelInputProps, SignalingChannelInputState> {
    constructor(props: SignalingChannelInputProps) {
        super(props);

        this.state = {
            signalingChannel: localStorage.getItem('signalingChannel') || '',
        }
    }

    onSignalingChannelChanged = (event: React.ChangeEvent<HTMLInputElement>) => {
        this.setState({signalingChannel: event.target.value});
        this.props.signalingChannelChanged(event.target.value);
    }

    render() {
        return (
            <Form>
                <Form.Group as={Row} className="mb-3"
                            controlId="KVSSignalingChannel">
                    <Form.Label column lg="2">Signaling Channel
                        Name</Form.Label>
                    <Col lg={true}>
                        <Form.Control type="text"
                                      placeholder="Signaling Channel Name"
                                      onChange={this.onSignalingChannelChanged}
                                      value={this.state.signalingChannel}/>
                    </Col>
                </Form.Group>
            </Form>
        )
    }
}

export default SignalingChannelInput;
