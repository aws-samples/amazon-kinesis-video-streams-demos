import React from 'react';
import {Col, Form, Row} from 'react-bootstrap';

interface CredentialsProviderProps {
    credentialsChanged: (akid: string,
                         skid: string,
                         stid: string) => void;
}

interface CredentialsProviderState {
    accessKey: string;
    secretKey: string;
    sessionToken: string;
}

class RegionSelector extends React.Component<CredentialsProviderProps, CredentialsProviderState> {
    constructor(props: CredentialsProviderProps) {
        super(props);

        this.state = {
            accessKey: localStorage.getItem('accessKey') || '',
            secretKey: localStorage.getItem('secretKey') || '',
            sessionToken: localStorage.getItem('sessionToken') || '',
        };
    }

    accessKeyChanged = (event: React.ChangeEvent<HTMLInputElement>) => {
        this.setState({accessKey: event.target.value})
        this.props.credentialsChanged(event.target.value, this.state.secretKey, this.state.sessionToken);
    }

    secretKeyChanged = (event: React.ChangeEvent<HTMLInputElement>) => {
        this.setState({secretKey: event.target.value})
        this.props.credentialsChanged(this.state.accessKey, event.target.value, this.state.sessionToken);
    }

    sessionTokenChanged = (event: React.ChangeEvent<HTMLInputElement>) => {
        this.setState({sessionToken: event.target.value})
        this.props.credentialsChanged(this.state.accessKey, this.state.secretKey, event.target.value);
    }

    render() {
        return (
            <Form className="p-0">
                <Form.Group as={Row} className="mb-3" controlId="AWSAccessKey">
                    <Form.Label column lg="2">Access Key</Form.Label>
                    <Col lg={true}>
                        <Form.Control type="text" placeholder="Access key"
                                      onChange={this.accessKeyChanged}
                                      value={this.state.accessKey}/>
                    </Col>
                </Form.Group>

                <Form.Group as={Row} className="mb-3" controlId="AWSSecretKey">
                    <Form.Label column lg="2">Secret Key</Form.Label>
                    <Col lg={true}>
                        <Form.Control type="password" placeholder="Secret key"
                                      onChange={this.secretKeyChanged}
                                      value={this.state.secretKey}/>
                    </Col>
                </Form.Group>

                <Form.Group as={Row} className="mb-3"
                            controlId="AWSSessionToken">
                    <Form.Label column lg="2">Session token</Form.Label>
                    <Col lg={true}>
                        <Form.Control type="password"
                                      placeholder="Session token (optional)"
                                      onChange={this.sessionTokenChanged}
                                      value={this.state.sessionToken}/>
                    </Col>
                </Form.Group>
            </Form>
        );
    }
}

export default RegionSelector;
