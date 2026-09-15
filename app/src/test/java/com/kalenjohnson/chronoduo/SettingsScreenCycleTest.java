package com.kalenjohnson.chronoduo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * JVM coverage for {@link SettingsScreen#cycleStepForward}/{@link
 * PartyPanelView#cycleStepBack} -- the wrap-around index math behind the
 * Mods page's option picker ("◂ label ▸" row). Regression test for the bug
 * where tapping the picker's forward ("▸") side did nothing when nothing was
 * selected yet (the trailing "None" slot, {@code curIdx == totalChoices - 1}):
 * the old code computed the next choice with a plain {@code curIdx + 1 <
 * choices.size()} bounds check instead of wrapping, so stepping forward from
 * "None" (choices.size(), i.e. totalChoices - 1) landed on
 * choices.size() + 1, never less than choices.size(), and produced "None"
 * again -- an apparent no-op. {@link SettingsScreen#cycleStepBack} already
 * used real modulo and was never affected; it's covered here for symmetry.
 */
public class SettingsScreenCycleTest {

    @Test
    public void forwardStepsThroughEveryChoiceThenWrapsToNone() {
        // 3 real choices + 1 trailing "None" slot = totalChoices 4;
        // "None" is index 3 (choices.size()).
        int total = 4;
        assertEquals(1, SettingsScreen.cycleStepForward(0, total));
        assertEquals(2, SettingsScreen.cycleStepForward(1, total));
        assertEquals(3, SettingsScreen.cycleStepForward(2, total)); // last choice -> None
        // The bug: stepping forward from None (curIdx == total - 1) must
        // wrap to the first choice (0), not silently produce None again.
        assertEquals(0, SettingsScreen.cycleStepForward(3, total));
    }

    @Test
    public void backStepsThroughEveryChoiceThenWrapsFromNone() {
        int total = 4;
        // Stepping back from None (curIdx == total - 1, i.e. 3) lands on the
        // last real choice (2) -- this direction always worked correctly.
        assertEquals(2, SettingsScreen.cycleStepBack(3, total));
        assertEquals(1, SettingsScreen.cycleStepBack(2, total));
        assertEquals(0, SettingsScreen.cycleStepBack(1, total));
        assertEquals(3, SettingsScreen.cycleStepBack(0, total)); // wraps to None
    }

    @Test
    public void forwardAndBackAreInverses() {
        int total = 5;
        for (int i = 0; i < total; i++) {
            assertEquals(i, SettingsScreen.cycleStepBack(SettingsScreen.cycleStepForward(i, total), total));
            assertEquals(i, SettingsScreen.cycleStepForward(SettingsScreen.cycleStepBack(i, total), total));
        }
    }
}
