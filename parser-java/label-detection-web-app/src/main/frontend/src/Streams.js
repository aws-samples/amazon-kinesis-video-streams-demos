import React, {useState, useEffect} from 'react';

import './App.css';
import {Card, Carousel} from "react-bootstrap";
import {Link} from "react-router-dom";
import axios from "axios";

const Streams = () => {
    const [streams, setStreams] = useState([]);
  
    const fetchStreams = () => {
      axios.get("http://localhost:8080/streams").then(res => {
        console.log(res);
        setStreams(res.data);
      });
    };
  
    useEffect(() => {
      fetchStreams();
    }, []);

    return (
      <React.Fragment>
      <h1>Archived Stream Segments</h1>
        <div class="stream-cards">
              {streams.map((stream, index) => (
                  <React.Fragment key={index}>
                      <Card style={{ width: '18rem',margin: '35px'}}>
                          <Card.Body>
                              <Card.Title>{stream.name}</Card.Title>
                              <Card.Text>Start Timestamp: {stream.startTimestamp}</Card.Text>
                              <Card.Text>End Timestamp: {stream.endTimestamp}</Card.Text>
                              <Link to={`/streams/${stream.id}`} className="btn btn-primary">View Rekgonized Images</Link>
                          </Card.Body>
                      </Card>
                  </React.Fragment>
              ))}
        </div>
      </React.Fragment>
    )
  };

  export default Streams;