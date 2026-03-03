package com.example.frauddetection.domain;

public class UserLinkageRule {
    private boolean enabled;
    private int linkageThreshold;

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLinkageThreshold() {
        return linkageThreshold;
    }

    public void setLinkageThreshold(int linkageThreshold) {
        this.linkageThreshold = linkageThreshold;
    }
}