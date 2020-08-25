import {Dropdown} from "react-bootstrap";
import React from 'react';

const DropdownLabel = (props) => {

    const handleClick = () => {
        props.onClick(props.index);
    }
    
    return (
        <React.Fragment>
            <Dropdown.Item onClick={handleClick} id="dropdown-labels-button" title="Labels">
                {props.label}
            </Dropdown.Item>  
        </React.Fragment>
    )
}

export default DropdownLabel;