package com.economic.dashboard.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BenchmarksTest {

    @Test
    public void affordabilityIndex_changesWithHomePriceAndRate() {
        double affordableHighRate = Benchmarks.mortgageAffordabilityIndex(
                10.0, 83730.0, 250000.0);
        double unaffordableHighRate = Benchmarks.mortgageAffordabilityIndex(
                10.0, 83730.0, 440600.0);

        assertTrue(affordableHighRate >= 1.0);
        assertTrue(unaffordableHighRate < 0.8);
    }

    @Test
    public void mortgageBadgeUsesAffordabilityNotRateAlone() {
        assertEquals("MODERATE", Benchmarks.mortgage30(2.5).word);
        assertEquals("CRITICAL", Benchmarks.mortgage30(6.48).word);
    }
}
