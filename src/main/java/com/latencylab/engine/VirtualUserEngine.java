package com.latencylab.engine;

import com.latencylab.model.Scenario;
import com.latencylab.model.VirtualUser;

import java.util.List;

public interface VirtualUserEngine {
    List<VirtualUser> initialize(Scenario scenario, int userCount);
    void execute(List<VirtualUser> users, Scenario scenario);
}
