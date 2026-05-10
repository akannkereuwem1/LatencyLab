package com.latencylab.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class VirtualUserTest {

    @Test
    void validConstructionAndAccessorRoundTrip() {
        RequestStep step = new RequestStep("name", HttpMethod.GET, "/api", null, Collections.emptyMap(), 1000);
        Scenario scenario = new Scenario("testName", Collections.singletonList(step), 60, 3600, 100);
        MetricsSnapshot snapshot = new MetricsSnapshot(100, 90, 10, 0, 0, 0, 0, 0, 0, 0.0, 0);

        VirtualUser user1 = new VirtualUser("user-1", VirtualUserState.RUNNING, scenario, snapshot);
        assertEquals("user-1", user1.userId());
        assertEquals(VirtualUserState.RUNNING, user1.state());
        assertEquals(scenario, user1.activeScenario());
        assertEquals(snapshot, user1.metricsSnapshot());

        VirtualUser user2 = new VirtualUser("user-2", VirtualUserState.IDLE, null, null);
        assertEquals("user-2", user2.userId());
        assertEquals(VirtualUserState.IDLE, user2.state());
        assertNull(user2.activeScenario());
        assertNull(user2.metricsSnapshot());
    }

    @Test
    void nullUserIdThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new VirtualUser(null, VirtualUserState.IDLE, null, null));
    }

    @Test
    void nullStateThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new VirtualUser("user-1", null, null, null));
    }
}
