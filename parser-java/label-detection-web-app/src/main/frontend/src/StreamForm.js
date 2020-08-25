import React from 'react';
import {Col, Button, Form} from "react-bootstrap";
import axios from "axios";
import moment from "moment";
import "./styles.css";




const StreamForm = props => {

    const onSubmit = (event) => {

        event.preventDefault();
        console.log(event.target[0].value);
        console.log(event.target[1].value);
        console.log(event.target[2].value);
        console.log(event.target[3].value);
        console.log(event.target[4].value);


        const startDatetime = event.target[1].value;
        const endDatetime = event.target[2].value;
        var formattedStartTimestamp = moment(startDatetime).format("DD/MM/yyyy");
        var formattedEndTimestamp = moment(endDatetime).format("DD/MM/yyyy");
        formattedStartTimestamp = formattedStartTimestamp + " " + startDatetime.substr(11);
        formattedEndTimestamp = formattedEndTimestamp + " " + endDatetime.substr(11);

        console.log(formattedStartTimestamp);
        console.log(formattedEndTimestamp);

        const postStreamInfo = () => {
            axios.post("http://localhost:8080/streams", {
                name: event.target[0].value,
                startTimestamp: formattedStartTimestamp,
                endTimestamp: formattedEndTimestamp,
                threads: event.target[3].value,
                sampleRate: event.target[4].value
            })
            .then(res => {
                console.log("Successfully submitted")
                console.log(res);
                props.history.push("/streams")
            })
            .catch(err => {
                console.log(err);
            })
        }
        postStreamInfo();
    }

    return (
        <React.Fragment>
            <h1>Enter Stream Information</h1>
            <Form onSubmit={onSubmit} id="form">
                <Form.Group controlId="formBasicStreamName">
                    <Form.Row className="form-group">  
                        <Col>
                            <Form.Label>Stream Name</Form.Label>
                            <Form.Control type="text" name="streamName" placeholder="Enter stream name"/>
                        </Col>
                    </Form.Row>
                </Form.Group>

                <Form.Group>
                    <Form.Row>
                        <Col>
                            <Form.Label>Start Timestamp</Form.Label>
                            <Form.Control type="datetime-local" step="1"/>
                        </Col>
                    </Form.Row>
                </Form.Group>

                <Form.Group>
                    <Form.Row>
                        <Col>
                            <Form.Label>End Timestamp</Form.Label>
                            <Form.Control type="datetime-local" step="1"/>
                        </Col>
                    </Form.Row>
                </Form.Group>

                <Form.Group controlId="formTimestamps">
                    <Form.Row className="form-group">
                        <Col>
                            <Form.Label>Threads</Form.Label>
                            <Form.Control type="text" name="threads" placeholder="Enter number of threads"/>
                        </Col>
                    </Form.Row>
                </Form.Group>
                
                <Form.Group>
                    <Form.Row className="form-group">
                        <Col>
                            <Form.Label>Sample Rate</Form.Label>
                            <Form.Control type="int" name="sampleRate" placeholder="Enter sample rate"/>
                        </Col>
                    </Form.Row>
                </Form.Group>

                <Button variant="primary" type="Submit">
                    Submit
                </Button>
            </Form>
        </React.Fragment>
    )
}

export default StreamForm;