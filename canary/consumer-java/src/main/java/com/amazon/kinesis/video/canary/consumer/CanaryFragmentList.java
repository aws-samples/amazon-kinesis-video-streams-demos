package com.amazon.kinesis.video.canary.consumer;

import java.util.Date;


/*
    Carries the trailing-window cursor across successive fragment-continuity checks.

    Each continuity tick lists only the fragments in (lastCheckTime, now] rather than the whole
    history since canary start. Holding just the cursor (instead of an ever-growing List<Fragment>)
    keeps memory flat and each ListFragments call cheap no matter how long the canary runs -- which
    is what makes continuous/soak runs viable. A null cursor means "first tick": the caller starts
    the window at canary start.
 */

public class CanaryFragmentList {
    private Date mLastCheckTime = null;

    public CanaryFragmentList() {
    }

    public void setLastCheckTime(Date lastCheckTime) {
        this.mLastCheckTime = lastCheckTime;
    }

    public Date getLastCheckTime() {
        return this.mLastCheckTime;
    }

}
