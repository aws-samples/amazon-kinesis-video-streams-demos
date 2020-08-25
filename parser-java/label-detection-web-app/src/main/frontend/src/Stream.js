import React, {useState, useEffect} from 'react';
import {useParams} from "react-router-dom";
import axios from "axios";
import {DropdownButton, Dropdown,} from "react-bootstrap";
import LoadingIndicator from "./LoadingIndicator.js";
import DropdownLabel from "./DropdownLabel.js";
import ControlledCarousel from "./ControlledCarousel.js";
import DropdownTimestamps from "./DropdownTimestamps";
import "./styles.css";

const Stream = () => {
    let {id} = useParams(); 
    const [Stream, setStream] = useState([]);
    const [loading, setLoading] = useState(false);

    const [TimestampsDisplaying, setTimestampsDisplaying] = useState([]);
    const [carouselIndex, setCarouselIndex] = useState(0);
    const [label, setLabel] = useState();
    
    const fetchStreamInfo = async () => {
      try {
        setLoading(true);
        axios.get("http://localhost:8080/streams/" + id).then(res => {
          console.log(res);
          console.log(res.data.length);
          setStream(res.data);
          setLoading(false);
        });
      } catch (err) {
        console.log(err);
        console.log("Failed to fetch stream info");
        setLoading(false);
      } 
    };


    useEffect(() => {
      fetchStreamInfo();
    }, []);
  
    var frames = Stream.frames; 
    var labelToTimestamps = Stream.labelToTimestamps;
    var timestamps;
    var labels;
    var timestampCollections;

    if (labelToTimestamps) {
        labels = Object.keys(labelToTimestamps);
        timestampCollections = Object.values(labelToTimestamps);
        
        if (TimestampsDisplaying.length <= 0) {
          var t = new Array(labels.length).fill(false)
          t[0] = true;

          setTimestampsDisplaying(t);  
          setLabel(labels[0]);
        }    
    }

    if (frames) {
      timestamps = frames.map(frame => frame.playbackTimestamp);
    }

    const handleLabelSelect = (index) => {
      let newArr = Array(TimestampsDisplaying.length).fill(false);
      newArr[index] = true;
      setTimestampsDisplaying(newArr);
      setLabel(labels[index]);
    }

    const handleCarouselIndexChange = (index) => {
      setCarouselIndex(index);
    }

  return (
    <React.Fragment>
      {loading ? <LoadingIndicator/> : <h2>{Stream.name} Frame Viewer</h2>}
      {loading ? null :
      <div id="dropdown-wrapper">
        <DropdownButton id="dropdown-labels-button" title="Labels" style={{margin:'7.5px'}}>
          {labels && labels.map((label, index) => (
            <DropdownLabel label={label} timestamps={timestampCollections} index={index} key={index} onClick={handleLabelSelect}></DropdownLabel>
          ))}
        </DropdownButton> 
        <DropdownButton id="dropdown-timestamps-button" title={"Frame timestamps for: " + label} class="dropdown" style={{margin:'7.5px'}} variant="secondary">  
          {timestampCollections && timestampCollections.map((timestampCollection, index) => (
            <DropdownTimestamps index={index} key={index} timestampsDisplaying={TimestampsDisplaying} labels={labels} timestampCollection={timestampCollection} timestamps={timestamps} onClick={handleCarouselIndexChange}></DropdownTimestamps>
          ))}
        </DropdownButton>
      </div>
      }
      <h1></h1>
      <ControlledCarousel frames={frames} index={carouselIndex} onSelect={handleCarouselIndexChange}>
      </ControlledCarousel> 
    </React.Fragment>
  )
};

  export default Stream;