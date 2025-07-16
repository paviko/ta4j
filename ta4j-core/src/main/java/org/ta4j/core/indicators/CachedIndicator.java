/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2023 Ta4j Organization & respective
 * authors (see AUTHORS)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.ta4j.core.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cached {@link Indicator indicator}.
 *
 * <p>
 * Caches the calculated results of the indicator to avoid calculating the same
 * index of the indicator twice. The caching drastically speeds up access to
 * indicator values. Caching is especially recommended when indicators calculate
 * their values based on the values of other indicators. Such nested indicators
 * can call {@link #getValue(int)} multiple times without the need to
 * {@link #calculate(int)} again.
 */
public abstract class CachedIndicator<T> extends AbstractIndicator<T> {

    /** List of cached results. */
    private final List<T> results;

    /**
     * Should always be the index of the last (calculated) result in
     * {@link #results}.
     */
    protected int highestResultIndex = -1;

    /** ReadWriteLock for thread-safe access to cache */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Whether to cache the last bar value */
    private final boolean cacheLastBar;

    /**
     * Constructor.
     *
     * @param series the bar series
     */
    protected CachedIndicator(BarSeries series) {
        this(series, false);
    }

    /**
     * Constructor.
     *
     * @param series the bar series
     * @param cacheLastBar whether to cache the last bar value
     */
    protected CachedIndicator(BarSeries series, boolean cacheLastBar) {
        super(series);
        this.cacheLastBar = cacheLastBar;
        int limit = series.getMaximumBarCount();
        this.results = limit == Integer.MAX_VALUE ? new ArrayList<>() : new ArrayList<>(limit);
    }

    /**
     * Constructor.
     *
     * @param indicator a related indicator (with a bar series)
     */
    protected CachedIndicator(Indicator<?> indicator) {
        this(indicator.getBarSeries(), false);
    }

    /**
     * Constructor.
     *
     * @param indicator a related indicator (with a bar series)
     * @param cacheLastBar whether to cache the last bar value
     */
    protected CachedIndicator(Indicator<?> indicator, boolean cacheLastBar) {
        this(indicator.getBarSeries(), cacheLastBar);
    }

    /**
     * @param index the bar index
     * @return the value of the indicator
     */
    protected abstract T calculate(int index);

    @Override
    public T getValue(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index cannot be negative: " + index);
        }
        BarSeries series = getBarSeries();
        if (series == null) {
            // Series is null; the indicator doesn't need cache.
            // (e.g. simple computation of the value)
            // --> Calculating the value
            T result = calculate(index);
            if (log.isTraceEnabled()) {
                log.trace("{}({}): {}", this, index, result);
            }
            return result;
        }

        // Series is not null

        final int removedBarsCount = series.getRemovedBarsCount();
        final int maximumResultCount = series.getMaximumBarCount();

        T result;
        if (index < removedBarsCount) {
            // Result already removed from cache - need write lock
            lock.writeLock().lock();
            try {
                if (log.isTraceEnabled()) {
                    log.trace("{}: result from bar {} already removed from cache, use {}-th instead",
                            getClass().getSimpleName(), index, removedBarsCount);
                }
                increaseLengthTo(removedBarsCount, maximumResultCount);
                highestResultIndex = removedBarsCount;
                result = results.get(0);
                if (result == null) {
                    // It should be "result = calculate(removedBarsCount);".
                    // We use "result = calculate(0);" as a workaround
                    // to fix issue #120 (https://github.com/mdeverdelhan/ta4j/issues/120).
                    result = calculate(0);
                    results.set(0, result);
                }
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            if (index == series.getEndIndex() && !cacheLastBar) {
                // Don't cache result if last bar (unless cacheLastBar is true)
                result = calculate(index);
            } else {
                // First try with read lock for the fast case where result is already cached
                lock.readLock().lock();
                try {
                    if (index <= highestResultIndex && !results.isEmpty()) {
                        // Result covered by current cache - fast path with read lock
                        int resultInnerIndex = results.size() - 1 - (highestResultIndex - index);
                        if (resultInnerIndex >= 0 && resultInnerIndex < results.size()) {
                            result = results.get(resultInnerIndex);
                            if (result != null) {
                                // Cache hit - return immediately
                                if (log.isTraceEnabled()) {
                                    log.trace("{}({}): {}", this, index, result);
                                }
                                return result;
                            }
                        }
                    }
                } finally {
                    lock.readLock().unlock();
                }

                // Need to calculate or cache is not ready - use write lock
                lock.writeLock().lock();
                try {
                    // Double-check pattern: another thread might have calculated the result
                    if (index <= highestResultIndex && !results.isEmpty()) {
                        int resultInnerIndex = results.size() - 1 - (highestResultIndex - index);
                        if (resultInnerIndex >= 0 && resultInnerIndex < results.size()) {
                            result = results.get(resultInnerIndex);
                            if (result != null) {
                                // Another thread calculated it while we were waiting
                                if (log.isTraceEnabled()) {
                                    log.trace("{}({}): {}", this, index, result);
                                }
                                return result;
                            }
                        }
                    }

                    increaseLengthTo(index, maximumResultCount);
                    if (index > highestResultIndex) {
                        // Result not calculated yet
                        highestResultIndex = index;
                        result = calculate(index);
                        results.set(results.size() - 1, result);
                    } else {
                        // Result covered by current cache but was null
                        int resultInnerIndex = results.size() - 1 - (highestResultIndex - index);
                        result = calculate(index);
                        results.set(resultInnerIndex, result);
                    }
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }
        if (log.isTraceEnabled()) {
            log.trace("{}({}): {}", this, index, result);
        }
        return result;
    }

    /**
     * Increases the size of the cached results buffer.
     *
     * @param index     the index to increase length to
     * @param maxLength the maximum length of the results buffer
     */
    private void increaseLengthTo(int index, int maxLength) {
        if (highestResultIndex > -1) {
            int newResultsCount = Math.min(index - highestResultIndex, maxLength);
            if (newResultsCount == maxLength) {
                results.clear();
                results.addAll(Collections.nCopies(maxLength, null));
            } else if (newResultsCount > 0) {
                results.addAll(Collections.nCopies(newResultsCount, null));
                removeExceedingResults(maxLength);
            }
        } else {
            // First use of cache
            assert results.isEmpty() : "Cache results list should be empty";
            results.addAll(Collections.nCopies(Math.min(index + 1, maxLength), null));
        }
    }

    /**
     * Removes the N first results which exceed the maximum bar count. (i.e. keeps
     * only the last maximumResultCount results)
     *
     * @param maximumResultCount the number of results to keep
     */
    private void removeExceedingResults(int maximumResultCount) {
        int resultCount = results.size();
        if (resultCount > maximumResultCount) {
            // Removing old results
            final int nbResultsToRemove = resultCount - maximumResultCount;
            if (nbResultsToRemove == 1) {
                results.remove(0);
            } else {
                results.subList(0, nbResultsToRemove).clear();
            }
        }
    }
}
