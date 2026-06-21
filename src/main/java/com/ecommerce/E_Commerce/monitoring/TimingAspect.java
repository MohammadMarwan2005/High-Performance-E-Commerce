package com.ecommerce.E_Commerce.monitoring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Performance-monitoring aspect (Phase-2 delivery requirement a).
 *
 * <p>An {@code @Around} advice that wraps every {@link Timed}-annotated method,
 * measures its wall-clock execution time, and emits a single structured log
 * line. It never alters arguments, return values, or control flow — it only
 * observes. This is the textbook AOP cross-cutting concern: timing is applied
 * uniformly without a single line of timing code inside the services.
 *
 * <p>Output format is intentionally {@code key=value} so it can be grepped and
 * parsed by the Step-5 benchmark:
 * <pre>
 *   op=OrderService.placeOrder durationMs=12.84 outcome=ok
 *   op=ProductService.findAll  durationMs=1.07  outcome=ok
 * </pre>
 */
@Aspect
@Component
public class TimingAspect {

    // Dedicated logger so timing lines are easy to isolate / route to a file.
    private static final Logger log = LoggerFactory.getLogger("PERF.TIMING");

    @Around("@annotation(timed)")
    public Object measure(ProceedingJoinPoint joinPoint, Timed timed) throws Throwable {
        String op = timed.value().isBlank()
                ? joinPoint.getSignature().getDeclaringType().getSimpleName()
                        + "." + joinPoint.getSignature().getName()
                : timed.value();

        long startNanos = System.nanoTime();
        String outcome = "ok";
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            // Record the failure but never swallow it — rethrow unchanged.
            outcome = "error:" + t.getClass().getSimpleName();
            throw t;
        } finally {
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            log.info("op={} durationMs={} outcome={}",
                    op, String.format("%.2f", durationMs), outcome);
        }
    }
}
