package com.amazon.kinesis.video.canary.consumer;

import java.math.BigInteger;

import com.amazonaws.services.kinesisvideo.model.Fragment;


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
