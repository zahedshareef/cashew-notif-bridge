package com.cashewbridge.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [CooldownPruner]. Covers the leak scenario (deleted
 * rules' cooldown entries linger forever) while staying independent of
 * Android and the service instance.
 */
class CooldownPruneTest {

    private val now = 1_700_000_000_000L

    @Test fun below_size_threshold_is_noop() {
        val map = mutableMapOf<Long, Long>()
        repeat(5) { i -> map[i.toLong()] = 0L /* ancient */ }
        CooldownPruner.prune(map, now)
        assertEquals("entries below threshold must not be pruned", 5, map.size)
    }

    @Test fun above_threshold_drops_expired_entries() {
        val map = mutableMapOf<Long, Long>()
        repeat(CooldownPruner.PRUNE_AT_SIZE) { i ->
            // Half fresh, half stale
            map[i.toLong()] = if (i % 2 == 0) now else now - CooldownPruner.MAX_AGE_MS - 1
        }
        CooldownPruner.prune(map, now)
        assertEquals(CooldownPruner.PRUNE_AT_SIZE / 2, map.size)
        assertTrue(
            "only fresh entries survive",
            map.values.all { it >= now - CooldownPruner.MAX_AGE_MS }
        )
    }

    @Test fun boundary_equal_to_cutoff_is_kept() {
        val map = mutableMapOf<Long, Long>()
        repeat(CooldownPruner.PRUNE_AT_SIZE) { i -> map[i.toLong()] = now }
        map[-1L] = now - CooldownPruner.MAX_AGE_MS  // exactly on boundary
        CooldownPruner.prune(map, now)
        assertTrue("boundary entry (== cutoff) must be kept", map.containsKey(-1L))
    }

    @Test fun entry_one_ms_before_cutoff_is_dropped() {
        val map = mutableMapOf<Long, Long>()
        repeat(CooldownPruner.PRUNE_AT_SIZE) { i -> map[i.toLong()] = now }
        map[-1L] = now - CooldownPruner.MAX_AGE_MS - 1
        CooldownPruner.prune(map, now)
        assertFalse("stale entry (< cutoff) must be dropped", map.containsKey(-1L))
    }
}
