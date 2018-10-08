package com.okta.sdk.impl.http;

import com.okta.sdk.impl.config.ClientConfiguration;
import com.okta.sdk.impl.http.support.BackoffStrategy;
import com.okta.sdk.lang.Assert;
import com.okta.sdk.lang.Collections;
import com.okta.sdk.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

public class RetryRequestExecutor implements RequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryRequestExecutor.class);

    /**
     * Maximum exponential back-off time before retrying a request
     */
    private static final int DEFAULT_MAX_BACKOFF_IN_MILLISECONDS = 20 * 1000;

    private static final int DEFAULT_MAX_RETRIES = 4;

    private int maxRetries = DEFAULT_MAX_RETRIES;

    private int maxElapsedMillis = 0;

    private BackoffStrategy backoffStrategy;

    private final RequestExecutor delegate;

    public RetryRequestExecutor(ClientConfiguration clientConfiguration, RequestExecutor delegate) {
        this.delegate = delegate;

        if (clientConfiguration.getRetryMaxElapsed() >= 0) {
            maxElapsedMillis = clientConfiguration.getRetryMaxElapsed() * 1000;
        }

        if (clientConfiguration.getRetryMaxAttempts() > 0) {
            maxRetries = clientConfiguration.getRetryMaxAttempts();
        }
    }

    @Override
    public Response executeRequest(Request request) throws RestException {

        Assert.notNull(request, "Request argument cannot be null.");

        int retryCount = 0;
        Response response = null;
        String requestId = null;
        Timer timer = new Timer();

        // Make a copy of the original request params and headers so that we can
        // permute them in the loop and start over with the original every time.
        QueryString originalQuery = new QueryString();
        originalQuery.putAll(request.getQueryString());

        HttpHeaders originalHeaders = new HttpHeaders();
        originalHeaders.putAll(request.getHeaders());

        while (true) {

            try {

                if (retryCount > 0) {
                    request.setQueryString(originalQuery);
                    request.setHeaders(originalHeaders);

                    // remember the request-id header if we need to retry
                    if (requestId == null) {
                        requestId = getRequestId(response);
                    }


                    InputStream content = request.getBody();
                    if (content != null && content.markSupported()) {
                        content.reset();
                    }
                }


                if (retryCount > 0) {
                    try {
                        // if we cannot pause, then return the original response
                        pauseBeforeRetry(retryCount, response, timer.split());
                    } catch (RestException e) {
                        if (log.isDebugEnabled()) {
                            log.warn("Unable to pause for retry: {}", e.getMessage(), e);
                        } else {
                            log.warn("Unable to pause for retry: {}", e.getMessage());
                        }

                        return response;
                    }
                }

                retryCount++;

                // include X-Okta headers when retrying
                setOktaHeaders(request, requestId, retryCount);

                response = delegate.executeRequest(request);

                //allow the loop to continue to execute a retry request
                if (!shouldRetry(response, retryCount, timer.split())) {
                    return response;
                }

            } catch (Exception t) {
                log.warn("Unable to execute HTTP request: ", t.getMessage(), t);

                if (!shouldRetry(t, retryCount, timer.split())) {
                    throw new RestException("Unable to execute HTTP request: " + t.getMessage(), t);
                }
            }
        }
    }

        /**
     * Exponential sleep on failed request to avoid flooding a service with
     * retries.
     *
     * @param retries           Current retry count.
     */
    private void pauseBeforeRetry(int retries, Response response, long timeElapsed) throws RestException {
        long delay = -1;
        long timeElapsedLeft = maxElapsedMillis - timeElapsed;

        // check before continuing
        if (!shouldRetry(retries, timeElapsed)) {
            throw failedToRetry();
        }

        if (backoffStrategy != null) {
            delay = Math.min(this.backoffStrategy.getDelayMillis(retries), timeElapsedLeft);
        } else if (response != null && response.getHttpStatus() == 429) {
            delay = get429DelayMillis(response);
            if (!shouldRetry(retries, timeElapsed + delay)) {
                throw failedToRetry();
            }
            log.debug("429 detected, will retry in {}ms, attempt number: {}", delay, retries);
        }

        // default / fallback strategy (backwards compatible implementation)
        if (delay < 0) {
            delay = Math.min(getDefaultDelayMillis(retries), timeElapsedLeft);
        }

        // this shouldn't happen, but guard against a negative delay at this point
        if (delay < 0) {
            throw failedToRetry();
        }

        log.debug("Retryable condition detected, will retry in {}ms, attempt number: {}", delay, retries);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestException(e.getMessage(), e);
        }
    }

    private long get429DelayMillis(Response response) {

        // the time at which the rate limit will reset, specified in UTC epoch time.
        String resetLimit = getOnlySingleHeaderValue(response,"X-Rate-Limit-Reset");
        if (resetLimit == null || !resetLimit.chars().allMatch(Character::isDigit)) {
            return -1;
        }

        // If the Date header is not set, do not continue
        Date requestDate = dateFromHeader(response);
        if (requestDate == null) {
            return -1;
        }

        long waitUntil = Long.parseLong(resetLimit) * 1000L;
        long requestTime = requestDate.getTime();
        long delay = waitUntil - requestTime + 1000;
        log.debug("429 wait: {} - {} + {} = {}", waitUntil, requestTime, 1000, delay);

        return delay;
    }

    private Date dateFromHeader(Response response) {
        Date result = null;
        long dateLong = response.getHeaders().getDate();
        if (dateLong > 0) {
            result = new Date(dateLong);
        }
        return result;
    }

    private long getDefaultDelayMillis(int retries) {
        long scaleFactor = 300;
        long result = (long) (Math.pow(2, retries) * scaleFactor);
        return Math.min(result, DEFAULT_MAX_BACKOFF_IN_MILLISECONDS);
    }

    /**
     * Returns true if a failed request should be retried.
     *
     * @param t       The throwable from the failed request.
     * @param retryCount  The number of times the current request has been attempted.
     * @param timeElapsed The time elapsed for this attempt.
     * @return True if the failed request should be retried.
     */
    private boolean shouldRetry(Throwable t, int retryCount, long timeElapsed) {
        if (!shouldRetry(retryCount, timeElapsed)) {
            return false;
        }

        if (t instanceof SocketException ||
                t instanceof SocketTimeoutException) {
            log.debug("Retrying on {}: {}", t.getClass().getName(), t.getMessage());
            return true;
        }

        return false;
    }

    private boolean shouldRetry(int retryCount, long timeElapsed) {
               // either maxRetries or maxElapsedMillis is enabled
        return (maxRetries > 0 || maxElapsedMillis > 0)

               // maxRetries count is disabled OR if set check it
               && (maxRetries <= 0 || retryCount <= this.maxRetries)

               // maxElapsedMillis is disabled OR if set check it
               && (maxElapsedMillis <= 0 || timeElapsed < maxElapsedMillis);
    }

    private boolean shouldRetry(Response response, int retryCount, long timeElapsed) {
        int httpStatus = response.getHttpStatus();

        // supported status codes
        return shouldRetry(retryCount, timeElapsed)
            && (httpStatus == 429
             || httpStatus == 503
             || httpStatus == 504);
    }

    private RestException failedToRetry() {
        return new RestException("Cannot retry request, next request will exceed retry configuration.");
    }

    private String getOnlySingleHeaderValue(Response response, String name) {

        if (response.getHeaders() != null) {
            List<String> values = response.getHeaders().get(name);
            if (!Collections.isEmpty(values) && values.size() == 1) {
                return values.get(0);
            }
        }
        return null;
    }

    private String getRequestId(Response response) {
        if (response != null) {
            return response.getHeaders().getFirst("X-Okta-Request-Id");
        }
        return null;
    }

    /**
     * Adds {@code X-Okta-Retry-For} and {@code X-Okta-Retry-Count} headers to request if not null/empty or zero.
     *
     * @param request the request to add headers too
     * @param requestId request ID of the original request that failed
     * @param retryCount the number of times the request has been retried
     */
    private void setOktaHeaders(Request request, String requestId, int retryCount) {
        if (Strings.hasText(requestId)) {
            request.getHeaders().add("X-Okta-Retry-For", requestId);
        }
        if (retryCount > 1) {
            request.getHeaders().add("X-Okta-Retry-Count", Integer.toString(retryCount));
        }
    }

    public BackoffStrategy getBackoffStrategy() {
        return this.backoffStrategy;
    }

    public void setBackoffStrategy(BackoffStrategy backoffStrategy) {
        this.backoffStrategy = backoffStrategy;
    }

    static class Timer {

        private ZonedDateTime startTime = ZonedDateTime.now();

        long split() {
            return startTime.until(ZonedDateTime.now(), ChronoUnit.MILLIS);
        }
    }

}
