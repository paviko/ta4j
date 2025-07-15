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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

/**
 * Base class for Exponential Moving Average implementations.
 */
public abstract class AbstractEMAIndicator extends RecursiveCachedIndicator<Num> {

    private static final Logger log = LoggerFactory.getLogger(AbstractEMAIndicator.class);
    
    // Static ThreadLocal to track recursion depth across all EMA indicators
    private static final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);
    
    private final Indicator<Num> indicator;
    private final int barCount;
    private final Num multiplier;

    /**
     * Constructor.
     * 
     * @param indicator  the {@link Indicator}
     * @param barCount   the time frame
     * @param multiplier the multiplier
     */
    protected AbstractEMAIndicator(Indicator<Num> indicator, int barCount, double multiplier) {
        super(indicator);
        this.indicator = indicator;
        this.barCount = barCount;
        this.multiplier = numOf(multiplier);
    }

    @Override
    protected Num calculate(int index) {
        String methodSignature = String.format("%s.calculate(%d)", this.getClass().getSimpleName(), index);
        
        // Track recursion depth to prevent StackOverflowError
        int currentDepth = recursionDepth.get();
        if (currentDepth > 100) {
            log.error("RECURSION LIMIT EXCEEDED: {} at depth {}, indicator: {}, barCount: {}", 
                methodSignature, currentDepth, indicator.getClass().getSimpleName(), barCount);
            log.error("Indicator chain: {}", getIndicatorChain());
            throw new RuntimeException("Recursion limit exceeded in EMA calculation - possible circular dependency");
        }
        
        if (currentDepth > 50) {
            log.warn("Deep recursion detected: {} at depth {}", methodSignature, currentDepth);
        }
        
        if (currentDepth % 10 == 0 && currentDepth > 10) {
            log.info("EMA recursion depth: {} for {}", currentDepth, methodSignature);
        }
        
        recursionDepth.set(currentDepth + 1);
        
        try {
            if (index == 0) {
                return indicator.getValue(0);
            }
            Num prevValue = getValue(index - 1);
            return indicator.getValue(index).minus(prevValue).multipliedBy(multiplier).plus(prevValue);
        } finally {
            recursionDepth.set(currentDepth);
        }
    }
    
    /**
     * Helper method to trace the indicator chain for debugging circular dependencies
     */
    private String getIndicatorChain() {
        StringBuilder chain = new StringBuilder();
        chain.append(this.getClass().getSimpleName()).append("(").append(barCount).append(")");
        
        if (indicator != null) {
            chain.append(" -> ").append(indicator.getClass().getSimpleName());
            if (indicator instanceof AbstractEMAIndicator) {
                AbstractEMAIndicator emaIndicator = (AbstractEMAIndicator) indicator;
                chain.append("(").append(emaIndicator.getBarCount()).append(")");
            }
        }
        
        return chain.toString();
    }
    
    /**
     * Clear recursion depth tracking for the current thread.
     * Should be called before starting a new calculation to reset state.
     */
    public static void clearRecursionTracking() {
        recursionDepth.remove();
        log.debug("Cleared EMA recursion tracking for current thread");
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " barCount: " + barCount;
    }

    public int getBarCount() {
        return barCount;
    }
}
