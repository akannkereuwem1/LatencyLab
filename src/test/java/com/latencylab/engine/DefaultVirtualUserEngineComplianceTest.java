package com.latencylab.engine;

import com.latencylab.engine.VirtualUserEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface compliance tests for DefaultVirtualUserEngine.
 */
class DefaultVirtualUserEngineComplianceTest {

    @Test
    void defaultVirtualUserEngineImplementsVirtualUserEngine() {
        assertTrue(VirtualUserEngine.class.isAssignableFrom(DefaultVirtualUserEngine.class),
                "DefaultVirtualUserEngine should implement VirtualUserEngine");
    }
}