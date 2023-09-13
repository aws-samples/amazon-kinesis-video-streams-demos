package com.amazon.kinesis.video.canary.consumer;

import java.util.List;
import java.util.ArrayList;


/*
    CanaryFragmentList allows for intervalMetricsTask calls to access the same data by passing a CanaryFragmentList object.
 */

public class CanaryFragmentList {
    private List<CanaryFragment> mFragmentList = new ArrayList<>();

    
    public CanaryFragmentList() {
    }

    public void setFragmentList(List<CanaryFragment> fragmentList) {
        this.mFragmentList = fragmentList;
    }

    public List<CanaryFragment> getFragmentList() {
        return this.mFragmentList;
    }

}
