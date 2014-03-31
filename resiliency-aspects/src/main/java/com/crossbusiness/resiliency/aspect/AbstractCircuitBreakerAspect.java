/**
 * The MIT License (MIT)
 *
 * Copyright (c)  2014 CrossBusiness, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.crossbusiness.resiliency.aspect;

import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;


import com.crossbusiness.resiliency.annotation.CircuitBreaker;
import com.crossbusiness.resiliency.exception.CircuitBreakerMethodExecutionException;
import com.crossbusiness.resiliency.exception.OpenCircuitException;


import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sumanth Chinthagunta <xmlking@gmail.com>
 *
 */
//TODO use both around and after() throwing
@Aspect
public abstract class AbstractCircuitBreakerAspect {
        // TODO implement statistics
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static CircuitBreakerMethodRegistry registry = new CircuitBreakerMethodRegistry();


    @Around("circuitBreakerAnnotatedClass(circuitBreakerConfig)")
    public Object circuitBreakerOnClassLevel(final ProceedingJoinPoint point,CircuitBreaker circuitBreakerConfig) throws Throwable {
        return  breakCircuit(point, circuitBreakerConfig);
    }
    @Around("circuitBreakerAnnotatedMethod(circuitBreakerConfig)")
    public Object circuitBreakerOnMethodLevel(final ProceedingJoinPoint point,CircuitBreaker circuitBreakerConfig) throws Throwable {
        return  breakCircuit(point, circuitBreakerConfig);
    }
    // @Around("circuitBreakerMethod(circuitBreakerConfig)")
    public Object breakCircuit(final ProceedingJoinPoint pjp, CircuitBreaker circuitBreakerConfig) throws Throwable {
        Object result = null;
            final String method = pjp.getSignature().toLongString();
            CircuitBreakerStatus status = null;
            try {
                    final MethodSignature sig = (MethodSignature) pjp.getStaticPart().getSignature();
                    registry.registeredMehtodIfnecessary(method, circuitBreakerConfig);
                    status = registry.getStatusWithHalfOpenExclusiveLockTry(method);
                    if (status.equals(CircuitBreakerStatus.OPEN)) {
                        log.info("CIRCUIT STATUS: OPEN. Method {} can not be executed. try later!", method);
                            throw new OpenCircuitException();
                    } else if (status.equals(CircuitBreakerStatus.HALF_OPEN)) {
                        log.info("CIRCUIT STATUS: HALF_OPEN. Another thread has the exclusive lock for half open. Method {} can not be executed.", method);
                            throw new OpenCircuitException();
                    } else if (status.equals(CircuitBreakerStatus.CLOSED)) {
                        log.info("CIRCUIT STATUS: CLOSED. execute method {}", method);
                            result = proceed(pjp);
                    } else if (status.equals(CircuitBreakerStatus.HALF_OPEN_EXCLUSIVE)) {
                        log.info("CIRCUIT STATUS: HALF_OPEN_EXCLUSIVE. This thread win the exclusive lock for the half open call. execute method: {}", method);
                            result = proceed(pjp);
                        log.info("CIRCUIT STATUS: HALF_OPEN_EXCLUSIVE. method execution was successfull. now close circuit for method {}", method);
                            registry.closeAndUnlock(method);
                    }

            } catch (CircuitBreakerMethodExecutionException e) {
                    Throwable throwable = e.getCause();
                    for (Class<? extends Throwable> clazz : registry.getfailureIndications(method)){
                            if (clazz.isAssignableFrom(throwable.getClass())) {
                                    // detected a failure
                                log.info("detected failure. failure indication: {} \nException:", clazz.getCanonicalName(), throwable);
                                    if (status.equals(CircuitBreakerStatus.CLOSED) && registry.sameClosedCycleInLocalAndGlobaleContext(method)) {
                                        log.info("Valid failure: method call and failure are in the same CLOSED cycle.");
                                            registry.addFailureAndOpenCircuitIfThresholdAchived(method);
                                    } else if (status.equals(CircuitBreakerStatus.HALF_OPEN_EXCLUSIVE)) {
                                            registry.keepOpenAndUnlock(method);
                                    }
                                    throw throwable;
                            }
                    }
                    // thrown exception is not a failureIndication
                    if (status.equals(CircuitBreakerStatus.HALF_OPEN_EXCLUSIVE)) {
                        log.info("CIRCUIT STATUS: HALF_OPEN_EXCLUSIVE. method execution was successfull. now close circuit for method {}", method);
                            registry.closeAndUnlock(method);
                    }
                    // throw the original method execution exception upper to the method invoker
                    throw throwable;
            } finally {
                    registry.cleanUp(method);
            }
            return result;
    }

    private Object proceed(ProceedingJoinPoint pjp) throws CircuitBreakerMethodExecutionException {
            try {
                    return pjp.proceed();
            } catch (Throwable t) {
                log.debug("Exception while method execution: {}", pjp.getSignature().toLongString());
                    throw new CircuitBreakerMethodExecutionException(t);
            }
    }

    protected static void setRegistry(CircuitBreakerMethodRegistry registry) {
            AbstractCircuitBreakerAspect.registry = registry;
    }

    public static Map<String, CircuitBreakerRegistryEntry> getCircuitBreakersMap() {
        return registry.getCircuitBreakersMap() ;
    }


    @Pointcut
    public abstract void circuitBreakerAnnotatedMethod(CircuitBreaker circuitBreakerConfig);

    @Pointcut
    public abstract void circuitBreakerAnnotatedClass(CircuitBreaker circuitBreakerConfig);

    /**
     * Return the set of joinpoints at which circuitBreaker advice should be applied.
     */
    @Pointcut
    public abstract void circuitBreakerMethod(CircuitBreaker circuitBreakerConfig);
}