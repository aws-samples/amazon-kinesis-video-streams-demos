package com.amazon.kinesis.video.canary.consumer;

import java.util.List;
import java.util.ArrayList;

import com.amazonaws.services.kinesisvideo.model.Fragment;



/*
    CanaryFragmentList allows for intervalMetricsTask calls to access a shared list of fragments.
 */

public class CanaryFragmentList {
    private List<Fragment> mFragmentList = new ArrayList<>();

    
    public CanaryFragmentList() {
    }

    public void setFragmentList(List<Fragment> fragmentList) {
        this.mFragmentList = fragmentList;
    }

    public List<Fragment> getFragmentList() {
        return this.mFragmentList;
    }

}
