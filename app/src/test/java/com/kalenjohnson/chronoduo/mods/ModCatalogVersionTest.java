package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM coverage for {@link ModCatalog#candidateBeatsBundled} -- the version-
 * comparison rule behind {@link ModCatalog#load} that decides whether a
 * freshly-fetched/cached remote catalog document should be preferred over
 * the bundled {@code assets/mods/catalog.json}.
 *
 * <p>Regression test for the "stale catalog text" bug: a device with a
 * previously-cached {@code mods_catalog.json} (or a remote {@code
 * catalog.json} that hadn't caught up with a bundled-only content change)
 * kept showing old {@code notes} strings forever, because the old
 * "remote/cache always wins over bundled once a fetch has ever succeeded"
 * rule had no way to tell a stale-but-successful fetch from a genuinely
 * newer one. The fix: only a STRICTLY higher {@code version} on the
 * candidate wins; an equal (including the common "both unversioned/legacy,
 * both 0") version prefers the bundled copy, since that's guaranteed to
 * match this build.
 *
 * <p>{@link ModCatalog#parseDocument}/{@code #parse} themselves can't be
 * exercised here -- see {@link ModCatalog}'s class doc: org.json's
 * android.jar copy is stub-only off-device, so any real JSON parsing throws
 * under this project's plain-JVM unit tests (confirmed empirically; see the
 * class doc's org.json caveat) -- {@code candidateBeatsBundled} is exactly
 * the piece of {@code load}'s logic split out so it CAN be tested without
 * that dependency.
 */
public class ModCatalogVersionTest {

    @Test
    public void higherCandidateVersionWins() {
        assertTrue(ModCatalog.candidateBeatsBundled(2, 1));
        assertTrue(ModCatalog.candidateBeatsBundled(5, 0));
    }

    @Test
    public void equalVersionPrefersBundled() {
        // The actual bug scenario: a committed/cached remote document that
        // hasn't caught up with a bundled-only change carries the SAME
        // version number (or, before catalog.json had a version field at
        // all, both sides are the legacy "0") -- bundled must win, not the
        // stale candidate.
        assertFalse(ModCatalog.candidateBeatsBundled(1, 1));
        assertFalse(ModCatalog.candidateBeatsBundled(0, 0));
    }

    @Test
    public void lowerCandidateVersionLoses() {
        assertFalse(ModCatalog.candidateBeatsBundled(0, 2));
        assertFalse(ModCatalog.candidateBeatsBundled(1, 3));
    }
}
