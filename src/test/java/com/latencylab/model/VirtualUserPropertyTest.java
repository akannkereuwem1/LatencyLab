package com.latencylab.model;

import net.jqwik.api.*;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class VirtualUserPropertyTest {

    @Provide
    Arbitrary<VirtualUserState> states() {
        return Arbitraries.of(VirtualUserState.class);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        RequestStep step = new RequestStep("name", HttpMethod.GET, "/path", null, Collections.emptyMap(), 100);
        Scenario scenario = new Scenario("test", Collections.singletonList(step), 0, 10, 1);
        return Arbitraries.just(scenario).injectNull(0.5);
    }

    @Provide
    Arbitrary<MetricsSnapshot> metricsSnapshots() {
        MetricsSnapshot snapshot = new MetricsSnapshot(10, 5, 5, 0, 0, 0, 0, 0, 0, 1.0, 0);
        return Arbitraries.just(snapshot).injectNull(0.5);
    }

    // Feature: latencylab-phase1-setup, Property 7: VirtualUser construction preserves all field values
    @Property(tries = 100)
    void constructionPreservesFieldValues(
            @ForAll String userId,
            @ForAll("states") VirtualUserState state,
            @ForAll("scenarios") Scenario activeScenario,
            @ForAll("metricsSnapshots") MetricsSnapshot metricsSnapshot
    ) {
        if (userId == null) return;

        VirtualUser user = new VirtualUser(userId, state, activeScenario, metricsSnapshot);

        assertEquals(userId, user.userId());
        assertEquals(state, user.state());
        assertEquals(activeScenario, user.activeScenario());
        assertEquals(metricsSnapshot, user.metricsSnapshot());
    }
}
