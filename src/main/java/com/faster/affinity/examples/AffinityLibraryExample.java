package com.faster.affinity.examples;

import com.faster.affinity.factory.AffinityLibrary;
import com.faster.affinity.factory.AffinityLibraryFactory;
import com.faster.affinity.factory.SystemValidationResult;
import com.faster.affinity.topology.TopologyDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple usage example and test
 */
public class AffinityLibraryExample {
    private static final Logger logger = LoggerFactory.getLogger(AffinityLibraryExample.class);

    public static void main(String[] args) {
        // Validate platform first
        SystemValidationResult validation = AffinityLibraryFactory.validatePlatform();
        System.out.println(validation.getDetailedReport());

        if (!validation.isSupported()) {
            System.err.println("Platform not supported, exiting.");
            return;
        }

        // Create library instance
        AffinityLibrary lib = AffinityLibraryFactory.getDefault();

        try {
            // Basic usage examples
            demonstrateBasicAffinity(lib);
            demonstrateTopologyDiscovery(lib);
            demonstratePerformanceMonitoring(lib);
            demonstrateNumaOperations(lib);

        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
        } finally {
            lib.shutdown();
        }
    }

    private static void demonstrateBasicAffinity(AffinityLibrary lib) {
        System.out.println("\n=== Basic Affinity Operations ===");

        // Get current thread affinity
        var currentAffinity = lib.getCurrentThreadAffinity();
        if (currentAffinity.isSuccess()) {
            System.out.println("Current thread can run on: " + currentAffinity.getValue());
        }

        // Set affinity to first 4 cores
        java.util.BitSet mask = new java.util.BitSet();
        mask.set(0, 4);

        var result = lib.setCurrentThreadAffinity(mask);
        if (result.isSuccess()) {
            System.out.println("Successfully set thread affinity to cores 0-3");
        } else {
            System.out.println("Failed to set affinity: " + result.getError());
        }
    }

    private static void demonstrateTopologyDiscovery(AffinityLibrary lib) {
        System.out.println("\n=== Topology Discovery ===");

        TopologyDetector.SystemTopology topology = lib.getSystemTopology();
        System.out.println("System: " + topology);

        // Get detailed core information
        var coreInfoResult = lib.getAllCoreInfo();
        if (coreInfoResult.isSuccess()) {
            coreInfoResult.getValue().forEach(coreInfo -> {
                System.out.println("  " + coreInfo);
            });
        }
    }

    private static void demonstratePerformanceMonitoring(AffinityLibrary lib) {
        System.out.println("\n=== Performance Monitoring ===");

        // Get system performance snapshot
        var perfResult = lib.getSystemPerformanceSnapshot();
        if (perfResult.isSuccess()) {
            System.out.println("System performance: " + perfResult.getValue());
        }

        // Find high utilization cores
        var highUtilResult = lib.getHighUtilizationCores(0.5);
        if (highUtilResult.isSuccess()) {
            System.out.println("High utilization cores (>50%): " + highUtilResult.getValue());
        }
    }

    private static void demonstrateNumaOperations(AffinityLibrary lib) {
        System.out.println("\n=== NUMA Operations ===");

        var caps = lib.getSystemCapabilities();
        if (!caps.isNumaAvailable()) {
            System.out.println("NUMA not available on this system");
            return;
        }

        // Get NUMA node information
        for (int nodeId = 0; nodeId < 2; nodeId++) {
            var nodeCpusResult = lib.getNumaNodeCpus(nodeId);
            var nodeMemoryResult = lib.getNumaNodeMemoryInfo(nodeId);

            if (nodeCpusResult.isSuccess() && nodeMemoryResult.isSuccess()) {
                System.out.printf("NUMA Node %d: CPUs=%s, Memory=%s%n",
                        nodeId, nodeCpusResult.getValue(), nodeMemoryResult.getValue());
            }
        }
    }
}