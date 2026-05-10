package com.latencylab.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyLabMain {
    private static final Logger logger = LoggerFactory.getLogger(LatencyLabMain.class);

    public static void main(String[] args) {
        boolean missingConfigValue = false;

        try {
            String version = LatencyLabMain.class.getPackage().getImplementationVersion();
            if (version == null || version.isEmpty()) {
                version = "unknown";
            }

            logger.info("Starting LatencyLab version {}", version);

            for (int i = 0; i < args.length; i++) {
                if ("--config".equals(args[i])) {
                    if (i == args.length - 1) {
                        logger.error("Missing value for --config parameter");
                        missingConfigValue = true;
                    } else {
                        String configPath = args[i + 1];
                        logger.info("Config path specified: {}", configPath);
                        i++;
                    }
                }
            }
        } catch (Exception e) {
            // Ignored to ensure logging exceptions do not propagate to the JVM
        }

        if (missingConfigValue) {
            System.exit(1);
        }
    }
}
