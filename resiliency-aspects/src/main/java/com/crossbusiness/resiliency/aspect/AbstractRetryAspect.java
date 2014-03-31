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

import com.crossbusiness.resiliency.annotation.Retry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;


/**
 * RetryOnFailureAspect
 * Advice that traps exceptions out of annotated calls and retries the call if appropriate.
 * <p/>
 * The execution of business services can sometimes fail due to concurrency issues (for example, deadlock loser).
 * If the operation is retried, it is quite likely to succeed next time round. For business services where it is appropriate to
 * retry in such conditions (idempotent operations that don't need to go back to the user for conflict resolution),
 * we'd like to transparently retry the operation to avoid the client seeing a PessimisticLockingFailureException.
 * This is a requirement that clearly cuts across multiple services in the service layer, and hence is ideal for implementing via an aspect.
 *
 * @author Sumanth Chinthagunta <xmlking@gmail.com>
 */
@Aspect
public abstract class AbstractRetryAspect {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final int DEFAULT_MAX_RETRIES = 2;

    private int maxRetries = DEFAULT_MAX_RETRIES;

    @Around("retryAnnotatedClass(retryConfig)")
    public Object timeoutOnClassLevel(final ProceedingJoinPoint point, Retry retryConfig) throws Throwable {
        return doRetry(point, retryConfig);
    }

    @Around("retryAnnotatedMethod(retryConfig)")
    public Object timeoutOnMethodLevel(final ProceedingJoinPoint point, Retry retryConfig) throws Throwable {
        return doRetry(point, retryConfig);
    }

    // @Around("retryMethodExecution(retryConfig)")
    public Object doRetry(ProceedingJoinPoint pjp, Retry retryConfig) throws Throwable {
        Class<? extends Exception>[] retryableExceptions = retryConfig.exceptions();

        int attempts = retryConfig.attempts();
        long delay = retryConfig.delay();
        if (!(attempts > 0)) {
            attempts = this.maxRetries;
        }

        log.info("Attempting operation with potential for {} with maximum {} retries", retryableExceptions, attempts);


        int numAttempts = 0;
        do {
            numAttempts++;
            try {
                return pjp.proceed();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (Throwable ex) {
                // if the exception is not what we're looking for, pass it through
                if (!isRetryableException(ex, retryableExceptions)) throw ex;

                // we caught the configured exception, retry unless we've reached the maximum
                if (numAttempts > attempts) {
                    log.warn("Exceeded maximum retries ({}), rethrowing Exception [{}]", attempts, ex);
                    throw ex;
                }

                log.info("Caught Exception: [{}]. \n\t\t Attempt#: {}, Will retry after {} {}",
                        new Object[]{ex, numAttempts, delay, retryConfig.unit()});

                if (delay > 0) {
                    retryConfig.unit().sleep(delay);
                }
            }
        } while (numAttempts <= attempts);
        // this will never execute - we will have either succesfully returned or rethrown an exception
        return null;
    }

    /**
     * Allow overriding of the default maximum number of retries.
     *
     * @param maxRetries maximum number of retries
     */
    @ManagedAttribute(description = "Through exception when Max Retries threshold is reached", defaultValue = "2")
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    static boolean isRetryableException(Throwable ex, Class<? extends Exception>[] retryableExceptions) {
        boolean isRetryableException = false;
        for (Class<? extends Exception> exception : retryableExceptions) {
            if (exception.isInstance(ex)) {
                isRetryableException = true;
                break;
            }
        }
        return isRetryableException;
    }

    @Pointcut
    public abstract void retryAnnotatedMethod(Retry governorConfig);

    @Pointcut
    public abstract void retryAnnotatedClass(Retry governorConfig);

    @Pointcut
    public abstract void retryMethodExecution(Retry governorConfig);
}