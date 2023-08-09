package com.amazon.kinesis.video.canary.consumer;

import java.math.BigInteger;

import com.amazonaws.services.kinesisvideo.model.Fragment;


/*
    CanaryFragment allows for retrieval of a Fragment's fragment number as a numeric.
    The Fragment class only has a fragment number getter that returns a String.
    Handling the conversion within this class allows for simple sorting using a Comparator.comparing call.
 */

public class CanaryFragment{
    private Integer fragmentNumberInt;
    private Fragment fragment;
    

    public CanaryFragment(Fragment fragment) {
        this.fragment = fragment;
    }

    public Fragment getFragment(){
        return this.fragment;
    }

    public BigInteger getFragmentNumberInt() {
        return new BigInteger(fragment.getFragmentNumber());
    }


}
