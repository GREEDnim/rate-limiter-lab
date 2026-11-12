package com.lld.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} whose time a test can advance. Provided so time-dependent
 * behaviour (window rollover) can be tested without sleeping or flakiness.
 */
public final class MutableClock extends Clock {

    // Volatile so it can be advanced from a test driver thread while worker threads
    // read it concurrently (as the rollover concurrency test does).
    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    /** Move time forward. */
    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    /** Jump to an exact instant. */
    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
