import {Dropdown} from "react-bootstrap";
import React from 'react';

const DropdownTimestamp = (props) => {

    const timestampsDisplaying = props.timestampsDisplaying;
    var isShowingTimestamps = timestampsDisplaying[props.index];

    return (
        <React.Fragment>
            {isShowingTimestamps ? <div> {props.timestampCollection.timestamps.map((timestamp, index) => (
                <div key={index}>
                    <Dropdown.Item onClick={() => props.onClick(props.timestamps.indexOf(timestamp))}>
                        {timestamp}
                    </Dropdown.Item>
                </div>
            ))}</div> : null}
        </React.Fragment>
    )
}

export default DropdownTimestamp;