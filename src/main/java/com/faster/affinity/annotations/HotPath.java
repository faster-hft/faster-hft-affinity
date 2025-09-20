package com.faster.affinity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods that are on the critical hot path for HFT applications.
 * These methods should be optimized for minimum latency and maximum throughput.
 *
 * While this doesn't provide JIT hints directly, it serves as documentation
 * and can be used by profiling tools to identify performance-critical code.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HotPath {

    /**
     * Description of why this method is on the hot path.
     */
    String value() default "";

    /**
     * Expected frequency of calls (calls per second).
     */
    long expectedFrequency() default -1;

    /**
     * Target latency in nanoseconds.
     */
    long targetLatencyNs() default -1;
}