package com.ecommerce.E_Commerce.monitoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose execution time should be measured by {@link TimingAspect}.
 *
 * <p>This is the AOP join-point selector for Phase-2 delivery requirement (a):
 * performance monitoring that lives entirely outside business code. A method
 * gains timing simply by being annotated — nothing inside it changes.
 *
 * <p>Applied to the critical paths (checkout, product read, batch job) so the
 * structured timing output can feed the Step-5 benchmark.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {

    /**
     * Optional human-readable operation label used in the timing log.
     * Defaults to {@code SimpleClassName.methodName} when left blank.
     */
    String value() default "";
}
