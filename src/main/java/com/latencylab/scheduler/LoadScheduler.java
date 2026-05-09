package com.latencylab.scheduler;

import com.latencylab.model.Scenario;

public interface LoadScheduler {
    void start(Scenario scenario);
    void pause();
    void stop();
    SchedulerState getState();
}
