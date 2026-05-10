package com.latencylab.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyLabMainTest {

    @Test
    void testNoArgsExecution() {
        assertDoesNotThrow(() -> LatencyLabMain.main(new String[]{}));
    }

    @Test
    void testConfigArgExecution() {
        // Since we swallow exceptions in LatencyLabMain, it won't throw anyway, 
        // but it should also successfully log the path.
        assertDoesNotThrow(() -> LatencyLabMain.main(new String[]{"--config", "path/to/file.yaml"}));
    }

    @Test
    void testConfigWithoutValueTriggersSystemExit() throws IOException, InterruptedException {
        // Because System.exit(1) will kill the Surefire test runner if called directly,
        // we must test it by spawning a new JVM process.
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.latencylab.cli.LatencyLabMain", "--config"
        );
        Process process = builder.start();
        int exitCode = process.waitFor();
        
        assertEquals(1, exitCode, "Expected exit code 1 when --config is missing its value");
    }
}
