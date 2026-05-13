package com.latencylab.transport;

import com.latencylab.transport.HttpTransportLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface compliance tests for OkHttpTransportLayer.
 */
class OkHttpTransportLayerComplianceTest {

    @Test
    void okHttpTransportLayerImplementsHttpTransportLayer() {
        assertTrue(HttpTransportLayer.class.isAssignableFrom(OkHttpTransportLayer.class),
                "OkHttpTransportLayer should implement HttpTransportLayer");
    }

    @Test
    void okHttpTransportLayerImplementsCloseable() {
        assertTrue(java.io.Closeable.class.isAssignableFrom(OkHttpTransportLayer.class),
                "OkHttpTransportLayer should implement java.io.Closeable");
    }
}