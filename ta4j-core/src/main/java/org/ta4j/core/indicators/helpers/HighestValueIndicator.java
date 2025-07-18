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
package org.ta4j.core.indicators.helpers;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Highest value indicator.
 * 
 * <p>
 * Returns the highest indicator value from the bar series within the bar count.
 */
public class HighestValueIndicator extends CachedIndicator<Num> {

    private final Indicator<Num> indicator;
    private final int barCount;
    
    // Monotonic deque for efficient sliding window maximum
    // Stores indices in decreasing order of their values
    private final Deque<Integer> maxDeque = new ArrayDeque<>();
    private int lastProcessedIndex = -1;

    /**
     * Constructor.
     * 
     * @param indicator the {@link Indicator}
     * @param barCount  the time frame
     */
    public HighestValueIndicator(Indicator<Num> indicator, int barCount) {
        this(indicator, barCount, false);
    }

    /**
     * Constructor.
     * 
     * @param indicator the {@link Indicator}
     * @param barCount  the time frame
     * @param fullyPreInitialized whether to skip lock mechanism
     */
    public HighestValueIndicator(Indicator<Num> indicator, int barCount, boolean fullyPreInitialized) {
        super(indicator, fullyPreInitialized);
        this.indicator = indicator;
        this.barCount = barCount;
    }

    @Override
    protected Num calculate(int index) {
        // Handle NaN case with recursive fallback (preserve original behavior)
        if (indicator.getValue(index).isNaN() && barCount != 1 && index > 0) {
            return new HighestValueIndicator(indicator, barCount - 1).getValue(index - 1);
        }
        
        // Check if this is sequential access from the last processed index
        if (index == lastProcessedIndex + 1 && !maxDeque.isEmpty()) {
            // Sequential access: use optimized sliding window approach
            return calculateOptimized(index);
        } else {
            // Non-sequential access or first calculation: use simple approach and prepare for optimization
            return calculateSimpleAndPrepareOptimization(index);
        }
    }
    
    /**
     * Simple O(k) calculation for non-sequential access, but prepares deque for future optimization
     */
    private Num calculateSimpleAndPrepareOptimization(int index) {
        int end = Math.max(0, index - barCount + 1);
        Num highest = indicator.getValue(index);
        for (int i = index - 1; i >= end; i--) {
            Num value = indicator.getValue(i);
            if (!value.isNaN() && (highest.isNaN() || highest.isLessThan(value))) {
                highest = value;
            }
        }
        
        // Prepare deque for potential future sequential access
        // Build the deque state for the current window
        maxDeque.clear();
        for (int i = end; i <= index; i++) {
            Num value = indicator.getValue(i);
            if (!value.isNaN()) {
                // Remove indices whose values are smaller than current value
                while (!maxDeque.isEmpty() && 
                       indicator.getValue(maxDeque.peekLast()).isLessThan(value)) {
                    maxDeque.pollLast();
                }
                maxDeque.offerLast(i);
            }
        }
        
        lastProcessedIndex = index;
        return highest;
    }
    
    /**
     * Optimized O(1) amortized calculation for sequential access using monotonic deque
     */
    private Num calculateOptimized(int index) {
        // Remove indices that are outside the current window
        while (!maxDeque.isEmpty() && maxDeque.peekFirst() < index - barCount + 1) {
            maxDeque.pollFirst();
        }
        
        // Remove indices whose values are smaller than current value
        // (they can never be maximum while current value is in window)
        Num currentValue = indicator.getValue(index);
        if (!currentValue.isNaN()) {
            while (!maxDeque.isEmpty() && 
                   indicator.getValue(maxDeque.peekLast()).isLessThan(currentValue)) {
                maxDeque.pollLast();
            }
            maxDeque.offerLast(index);
        }
        
        lastProcessedIndex = index;
        
        // The front of deque contains the index of maximum value in current window
        if (!maxDeque.isEmpty()) {
            return indicator.getValue(maxDeque.peekFirst());
        } else {
            // All values in window are NaN
            return currentValue; // which is NaN
        }
    }

    /** @return {@link #barCount} */
    @Override
    public int getUnstableBars() {
        return barCount;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " barCount: " + barCount;
    }
}
