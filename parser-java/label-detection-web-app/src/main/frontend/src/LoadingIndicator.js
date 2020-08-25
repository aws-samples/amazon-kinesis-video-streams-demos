import Loader from 'react-loader-spinner';
import React from 'react';

const LoadingIndicator = () => {
    return (
    <React.Fragment>
      <h1>Fetching media from Kinesis Video Streams!</h1>
      <Loader type="ThreeDots" color="7FFFD4" height="100" width="100"/>
    </React.Fragment>
    )
}

export default LoadingIndicator;