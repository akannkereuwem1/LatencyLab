package com.latencylab.parser;

import com.latencylab.model.Scenario;

public interface ScenarioParser {
    Scenario parse(String filePath);
    boolean validate(Scenario scenario);
}
