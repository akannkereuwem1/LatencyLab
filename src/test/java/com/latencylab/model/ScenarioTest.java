package com.latencylab.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioTest {

    private RequestStep createStep() {
        return new RequestStep("name", HttpMethod.GET, "/api", null, Collections.emptyMap(), 1000);
    }

    @Test
    void validConstructionAndAccessorRoundTrip() {
        List<RequestStep> steps = List.of(createStep());
        Scenario scenario = new Scenario("testName", steps, 60, 3600, 100);

        assertEquals("testName", scenario.testName());
        assertEquals(steps, scenario.steps());
        assertEquals(60, scenario.rampUpSeconds());
        assertEquals(3600, scenario.durationSeconds());
        assertEquals(100, scenario.userCount());
    }

    @Test
    void emptyStepsThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", Collections.emptyList(), 0, 10, 1));
    }

    @Test
    void outOfRangeRampUpSecondsThrowsException() {
        List<RequestStep> steps = List.of(createStep());
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, -1, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, 3601, 10, 1));
    }

    @Test
    void outOfRangeDurationSecondsThrowsException() {
        List<RequestStep> steps = List.of(createStep());
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, 0, 86401, 1));
    }

    @Test
    void outOfRangeUserCountThrowsException() {
        List<RequestStep> steps = List.of(createStep());
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, 0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> 
            new Scenario("test", steps, 0, 10, 100001));
    }

    @Test
    void stepsListIsDefensivelyCopied() {
        List<RequestStep> originalSteps = new ArrayList<>();
        originalSteps.add(createStep());

        Scenario scenario = new Scenario("test", originalSteps, 0, 10, 1);

        originalSteps.add(createStep());

        assertEquals(1, scenario.steps().size());
        assertThrows(UnsupportedOperationException.class, () -> scenario.steps().add(createStep()));
    }
}
