package com.amazon.kinesis.video.canary.consumer;

import java.math.BigInteger;

import com.amazonaws.services.kinesisvideo.model.Fragment;


public class CanaryFragment{
    private Integer fragmentNumberInt;


    public Fragment fragment;

    public BigInteger getFragmentNumberInt() {
        return new BigInteger(fragment.getFragmentNumber());
    }

    public CanaryFragment(Fragment fragment) {
        this.fragment = fragment;
    }

}
