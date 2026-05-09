package com.latencylab.model;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioPropertyTest {

    @Provide
    Arbitrary<RequestStep> requestSteps() {
        Arbitrary<String> names = Arbitraries.strings().ofMinLength(1);
        Arbitrary<HttpMethod> methods = Arbitraries.of(HttpMethod.class);
        Arbitrary<String> endpoints = Arbitraries.strings().ofMinLength(1);
        Arbitrary<String> bodies = Arbitraries.strings();
        Arbitrary<Map<String, String>> headers = Arbitraries.maps(
                Arbitraries.strings().ofMinLength(1),
                Arbitraries.strings().ofMinLength(1)
        ).ofMaxSize(5);
        Arbitrary<Integer> timeouts = Arbitraries.integers().between(1, 300000);

        return Combinators.combine(names, methods, endpoints, bodies, headers, timeouts)
                .as(RequestStep::new);
    }

    // Feature: latencylab-phase1-setup, Property 3: Scenario construction preserves all field values
    @Property(tries = 100)
    void constructionPreservesFieldValues(
            @ForAll String testName,
            @ForAll("requestSteps") @Size(min = 1, max = 10) List<RequestStep> steps,
            @ForAll @IntRange(min = 0, max = 3600) int rampUpSeconds,
            @ForAll @IntRange(min = 1, max = 86400) int durationSeconds,
            @ForAll @IntRange(min = 1, max = 100000) int userCount
    ) {
        if (testName == null) return;

        Scenario scenario = new Scenario(testName, steps, rampUpSeconds, durationSeconds, userCount);

        assertEquals(testName, scenario.testName());
        assertEquals(steps, scenario.steps());
        assertEquals(rampUpSeconds, scenario.rampUpSeconds());
        assertEquals(durationSeconds, scenario.durationSeconds());
        assertEquals(userCount, scenario.userCount());
    }

    // Feature: latencylab-phase1-setup, Property 4: Scenario rejects invalid numeric bounds
    @Property(tries = 100)
    void rejectsInvalidNumericBounds(
            @ForAll String testName,
            @ForAll("requestSteps") @Size(min = 1, max = 10) List<RequestStep> steps,
            @ForAll int rampUpSeconds,
            @ForAll int durationSeconds,
            @ForAll int userCount
    ) {
        if (testName == null) return;
        Assume.that(rampUpSeconds < 0 || rampUpSeconds > 3600 || 
                    durationSeconds < 1 || durationSeconds > 86400 || 
                    userCount < 1 || userCount > 100000);

        assertThrows(IllegalArgumentException.class, () -> {
            new Scenario(testName, steps, rampUpSeconds, durationSeconds, userCount);
        });
    }

    // Feature: latencylab-phase1-setup, Property 8: Record immutability — steps list is defensively copied
    @Property(tries = 100)
    void stepsListIsDefensivelyCopied(
            @ForAll String testName,
            @ForAll("requestSteps") RequestStep singleStep,
            @ForAll("requestSteps") RequestStep anotherStep,
            @ForAll @IntRange(min = 0, max = 3600) int rampUpSeconds,
            @ForAll @IntRange(min = 1, max = 86400) int durationSeconds,
            @ForAll @IntRange(min = 1, max = 100000) int userCount
    ) {
        if (testName == null) return;

        List<RequestStep> originalSteps = new ArrayList<>();
        originalSteps.add(singleStep);

        Scenario scenario = new Scenario(testName, originalSteps, rampUpSeconds, durationSeconds, userCount);

        // Modify the original list
        originalSteps.add(anotherStep);

        // The scenario's steps should not contain the new step
        assertEquals(1, scenario.steps().size());
        assertFalse(scenario.steps().contains(anotherStep));

        // Ensure the returned list is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            scenario.steps().add(anotherStep);
        });
    }
}
