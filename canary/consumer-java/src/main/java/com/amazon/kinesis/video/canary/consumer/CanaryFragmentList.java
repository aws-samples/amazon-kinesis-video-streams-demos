package com.amazon.kinesis.video.canary.consumer;

import java.util.List;
import java.util.ArrayList;


public class CanaryFragmentList {

    private List<CanaryFragment> fragmentList = new ArrayList<>();

    public CanaryFragmentList() {}

    public void setFragmentList(List<CanaryFragment> fragmentList)
    {
        this.fragmentList = fragmentList;
    }

    public List<CanaryFragment> getFragmentList()
    {
        return fragmentList;
    }

}