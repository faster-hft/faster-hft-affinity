package com.faster.affinity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods that are on the cold path (error handling, initialization, etc.).
 * These methods are called infrequently and should not be optimized at the expense of hot path performance.
 *
 * This serves as documentation for profiling tools and developers.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ColdPath {

    /**
     * Description of why this method is on the cold path.
     */
    String value() default "";
}