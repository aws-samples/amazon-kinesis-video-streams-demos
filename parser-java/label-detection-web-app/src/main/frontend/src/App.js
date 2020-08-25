import React from 'react';
import './App.css';
import StreamForm from "./StreamForm.js";
import Streams from "./Streams.js";
import Stream from "./Stream.js";
import {BrowserRouter as Router, Route} from "react-router-dom";

function App() {  
  return (
  <Router>
    <div className="App">
      <Route exact path="/" component={StreamForm}/>    
      <Route exact path="/streams" component={Streams}/>
      <Route exact path="/streams/:id">
        <Stream/>
      </Route>
    </div>
  </Router>
  );
}

export default App;