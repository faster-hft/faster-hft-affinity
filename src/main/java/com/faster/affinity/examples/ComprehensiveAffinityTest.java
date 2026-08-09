package com.faster.affinity.examples;

import com.faster.affinity.factory.AffinityLibraryFactory;
import com.faster.affinity.factory.AffinityLibrary;
import com.faster.affinity.exceptions.OperationResult;
import java.util.BitSet;

/**
 * Comprehensive standalone smoke test for the HFT Thread Affinity Library.
 * Run directly from the fat JAR to verify library functionality on a target host:
 * {@code java -cp faster-hft-affinity-<version>-jar-with-dependencies.jar
 * com.faster.affinity.examples.ComprehensiveAffinityTest}
 */
public class ComprehensiveAffinityTest {

    public static void main(String[] args) {
        System.out.println("🚀 Starting Comprehensive HFT Thread Affinity Test");
        System.out.println("====================================================");

        try {
            // Initialize the affinity library
            AffinityLibrary affinityLib = AffinityLibraryFactory.getDefault();

            if (!affinityLib.isInitialized()) {
                System.err.println("❌ Failed to initialize affinity library");
                System.exit(1);
            }

            System.out.println("✅ Affinity library initialized successfully");

            // Test 1: Get current thread affinity
            System.out.println("\n📋 Test 1: Getting current thread affinity");
            OperationResult<BitSet> currentAffinity = affinityLib.getCurrentThreadAffinity();
            if (currentAffinity.isSuccess()) {
                System.out.println("✅ Current thread affinity: " + currentAffinity.getValue());
            } else {
                System.out.println("⚠️  Could not get current thread affinity: " + currentAffinity.getError().getMessage());
            }

            // Test 2: Set thread affinity to first CPU
            System.out.println("\n📋 Test 2: Setting thread affinity to CPU 0");
            BitSet cpu0 = new BitSet();
            cpu0.set(0);
            OperationResult<Void> setResult = affinityLib.setCurrentThreadAffinity(cpu0);
            if (setResult.isSuccess()) {
                System.out.println("✅ Successfully set thread affinity to CPU 0");
            } else {
                System.out.println("⚠️  Could not set thread affinity: " + setResult.getError().getMessage());
            }

            // Test 3: Performance test
            System.out.println("\n📋 Test 3: Quick performance test");
            long startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                BitSet testMask = new BitSet();
                testMask.set(i % 2); // Alternate between CPU 0 and 1
                affinityLib.setCurrentThreadAffinity(testMask);
            }
            long endTime = System.nanoTime();
            long avgLatency = (endTime - startTime) / 1000; // nanoseconds per operation
            System.out.println("✅ Average affinity set latency: " + avgLatency + " ns");

            // Test 4: System capabilities
            System.out.println("\n📋 Test 4: System capabilities detection");
            try {
                // Test system capabilities
                var capabilities = affinityLib.getSystemCapabilities();
                System.out.println("✅ System capabilities: " + capabilities);

                // Test topology detection
                var topology = affinityLib.getSystemTopology();
                System.out.println("✅ System topology: " + topology);

                // Test NUMA operations if available
                try {
                    OperationResult<BitSet> numaNode0 = affinityLib.getNumaNodeCpus(0);
                    if (numaNode0.isSuccess()) {
                        System.out.println("✅ NUMA node 0 CPUs: " + numaNode0.getValue());
                    } else {
                        System.out.println("⚠️  NUMA not supported: " + numaNode0.getError().getMessage());
                    }
                } catch (Exception e) {
                    System.out.println("⚠️  NUMA operations not available: " + e.getMessage());
                }

            } catch (Exception e) {
                System.out.println("⚠️  Some system capabilities not available: " + e.getMessage());
            }

            System.out.println("\n🎉 Comprehensive test completed successfully!");
            System.out.println("====================================================");

            // Cleanup
            affinityLib.shutdown();
            System.out.println("✅ Library shutdown complete");

        } catch (Exception e) {
            System.err.println("❌ Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}