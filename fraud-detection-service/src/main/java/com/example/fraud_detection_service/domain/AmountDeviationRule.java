package com.example.fraud_detection_service.domain;

public class AmountDeviationRule {
    private boolean enabled;
    private int sigmaThreshold;
    private int historyDuration;
    private TimeUnit timeUnit;
    private int minTransactions;

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSigmaThreshold() {
        return sigmaThreshold;
    }

    public void setSigmaThreshold(int sigmaThreshold) {
        this.sigmaThreshold = sigmaThreshold;
    }

    public int getHistoryDuration() {
        return historyDuration;
    }

    public void setHistoryDuration(int historyDuration) {
        this.historyDuration = historyDuration;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    public int getMinTransactions() {
        return minTransactions;
    }

    public void setMinTransactions(int minTransactions) {
        this.minTransactions = minTransactions;
    }
}
