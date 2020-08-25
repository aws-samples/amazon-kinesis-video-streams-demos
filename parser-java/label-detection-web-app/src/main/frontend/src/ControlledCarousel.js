import "./styles.css";
import {Carousel} from "react-bootstrap"
import React from 'react';



const ControlledCarousel = (props) => {

    const handleSelect = (selectedIndex, e) => {
        props.onSelect(selectedIndex);
    };

    const frames = props.frames;

    return (
        <Carousel activeIndex={props.index} onSelect={handleSelect}>
            {frames && frames.map((frame, index) => (
              <Carousel.Item key={index}>
                <img src={`data:image/png;base64,${frame.imageBytes}`}/>
                <Carousel.Caption>
                  <h1>{frame.playbackTimestamp}</h1>
                </Carousel.Caption>
              </Carousel.Item>
            ))}
        </Carousel>
    )
}

export default ControlledCarousel;