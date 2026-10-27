package com.github.ifrugal.gateway.core.filter;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Micrometer context-propagation {@link ThreadLocalAccessor} that bridges the
 * request id stored in the Reactor Context under
 * {@link LoggingAndCachingWebFilter#REQUEST_ID_CONTEXT_KEY} to an SLF4J MDC entry.
 *
 * <p>Once registered with {@code ContextRegistry} and with Reactor automatic
 * context propagation enabled, Reactor sets the MDC entry whenever it runs code
 * on behalf of a request (subscription, signals, scheduler hops) and removes or
 * restores it afterwards, so ids never leak between requests that share
 * Netty event-loop threads.</p>
 *
 * <p>Only the single configured MDC key is touched; other MDC entries are left alone.</p>
 */
public class RequestIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    private final String mdcKey;

    /**
     * @param mdcKey the SLF4J MDC key to write the request id to
     */
    public RequestIdThreadLocalAccessor(String mdcKey) {
        this.mdcKey = mdcKey;
    }

    /**
     * @return the Reactor Context key this accessor reads, {@link LoggingAndCachingWebFilter#REQUEST_ID_CONTEXT_KEY}
     */
    @Override
    public Object key() {
        return LoggingAndCachingWebFilter.REQUEST_ID_CONTEXT_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(mdcKey);
    }

    @Override
    public void setValue(String value) {
        if (value == null) {
            MDC.remove(mdcKey);
        } else {
            MDC.put(mdcKey, value);
        }
    }

    @Override
    public void reset() {
        MDC.remove(mdcKey);
    }

    /**
     * @return the MDC key this accessor writes
     */
    public String getMdcKey() {
        return mdcKey;
    }
}
