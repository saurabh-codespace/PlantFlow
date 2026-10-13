package com.plantflow.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class OperationAspect {
    private static final Logger log=LoggerFactory.getLogger(OperationAspect.class);
    private final MeterRegistry registry;
    public OperationAspect(MeterRegistry registry) { this.registry=registry; }
    @Around("@within(org.springframework.web.bind.annotation.RestController) && !@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object measure(ProceedingJoinPoint point) throws Throwable {
        long start=System.nanoTime();String result="success";
        try { return point.proceed(); }
        catch(Throwable error) { result="failure";throw error; }
        finally {
            long elapsed=System.nanoTime()-start;
            String operation=point.getSignature().getDeclaringType().getSimpleName()+"."+point.getSignature().getName();
            Timer.builder("plantflow.http.operation").tag("operation",operation).tag("outcome",result).register(registry).record(elapsed,TimeUnit.NANOSECONDS);
            // Do not include arguments, JWTs, coordinates or personal data in operational logs.
            log.debug("operation={} outcome={} durationMs={}",operation,result,elapsed/1_000_000);
        }
    }
}
