package com.latencylab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class InterfacePresenceTest {

    @Test
    void verifyInterfacesPresent() {
        assertDoesNotThrow(() -> Class.forName("com.latencylab.parser.ScenarioParser"));
        assertDoesNotThrow(() -> Class.forName("com.latencylab.scheduler.LoadScheduler"));
        assertDoesNotThrow(() -> Class.forName("com.latencylab.engine.VirtualUserEngine"));
        assertDoesNotThrow(() -> Class.forName("com.latencylab.transport.HttpTransportLayer"));
        assertDoesNotThrow(() -> Class.forName("com.latencylab.metrics.MetricsEngine"));
        assertDoesNotThrow(() -> Class.forName("com.latencylab.reporting.ReportingEngine"));
    }
}
