package com.faster.affinity.platform;

import com.faster.affinity.config.AffinityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating platform-specific providers
 */
public class PlatformProviderFactory {
    private static final Logger logger = LoggerFactory.getLogger(PlatformProviderFactory.class);

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    private static final boolean IS_WINDOWS = OS_NAME.contains("windows");

    public static PlatformProvider createProvider(AffinityConfig config) {
        try {
            if (IS_WINDOWS) {
                logger.info("Creating Windows platform provider");
                return new WindowsPlatformProvider(config);
            } else if (IS_LINUX) {
                logger.info("Creating Linux platform provider");
                return new LinuxPlatformProvider(config);
            } else {
                logger.warn("Unknown platform {}, no provider available", OS_NAME);
                if (config.isDeveloperMode()) {
                    logger.info("Developer mode enabled, creating mock provider for unsupported platform: {}", OS_NAME);
                    return new MockPlatformProvider(config);
                }
                throw new UnsupportedOperationException("Platform not supported: " + OS_NAME);
            }
        } catch (Exception e) {
            logger.error("Failed to create platform provider", e);
            throw new RuntimeException("Failed to create platform provider", e);
        }
    }

    public static boolean isPlatformSupported() {
        return IS_WINDOWS || IS_LINUX;
    }

    public static String getPlatformName() {
        return OS_NAME;
    }
}