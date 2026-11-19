package ca.mslab.forge.data

import ca.mslab.forge.ui.usageStale
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ProviderUsageTest {
    /** Recorded from the live Provider Usage 0.12.0 endpoint on 2026-11-04 (identity fields removed). */
    private val live = """
        {"detected_providers":[{"provider":"claude","via":"cli"},{"provider":"codex","via":"cli"}],
         "generated_at":"2026-11-04T04:09:19Z","codexbar":{"source":"settings","installed":true,"version":"0.83.0"},
         "warn_threshold":75,"high_threshold":90,"status_bar_mode":"off","poll_minutes":10,
         "providers":[
          {"provider":"claude","plan":"Claude Max 5x","windows":[
            {"label":"5-hour","utilization_pct":2,"reset_at":"2026-11-04T09:00:00Z","reset_description":"Nov 4 at 9:00AM","window_seconds":18000},
            {"label":"weekly","utilization_pct":0,"reset_at":"2026-11-09T02:00:00Z","window_seconds":604800},
            {"label":"Fable only","utilization_pct":0,"reset_at":"2026-11-09T02:00:00Z","scoped":true,"window_seconds":604800}],
           "fetched_at":"2026-11-04T04:09:19Z","source":"oauth"},
          {"provider":"codex","plan":"prolite","windows":[
            {"label":"weekly","utilization_pct":31,"reset_at":"2026-11-05T12:06:07Z","window_seconds":604800,
             "pace":{"summary":"50% in reserve","stage":"farBehind"}}],
           "fetched_at":"2026-11-04T04:09:19Z","source":"oauth","reset_credits":{"available_count":3}}],
         "unavailable":[]}
    """.trimIndent()

    @Test fun `live payload keeps Codex and Claude first with only the windows they report`() {
        val report = parseUsageReport(live)
        assertEquals(listOf("codex", "claude"), report.providers.map { it.id })
        assertEquals(10, report.pollMinutes)
        val codex = report.providers[0]
        // Codex reported no 5-hour window: none is invented.
        assertEquals(listOf(WindowKind.Weekly), codex.headline.map { it.kind })
        assertEquals(69.0, codex.headline.single().remainingPercent, 0.0)
        assertEquals(Instant.parse("2026-11-05T12:06:07Z"), codex.headline.single().resetAt)
        val claude = report.providers[1]
        assertEquals(listOf(WindowKind.FiveHour, WindowKind.Weekly), claude.headline.map { it.kind })
        assertEquals("Claude Max 5x", claude.plan)
        // The model-scoped weekly window is kept for details but never replaces the account-wide one.
        assertEquals(3, claude.windows.size)
        assertFalse(claude.headline.any { it.scoped })
        assertEquals("Fable only", claude.windows.last().title)
    }

    @Test fun `window kind comes from the reported duration, under any known field name`() {
        fun kindOf(field: String) = parseUsageReport("""{"providers":[{"provider":"x","windows":[
            {"label":"anything","utilization_pct":10,$field}]}]}""").providers.single().windows.single().kind
        assertEquals(WindowKind.FiveHour, kindOf("\"window_seconds\":18000"))
        assertEquals(WindowKind.FiveHour, kindOf("\"windowDurationMins\":300"))
        assertEquals(WindowKind.Weekly, kindOf("\"window_minutes\":10080"))
        assertEquals(WindowKind.Weekly, kindOf("\"windowMinutes\":10080"))
        assertEquals(WindowKind.Other, kindOf("\"window_seconds\":2592000"))
        // A label alone never decides the kind.
        assertEquals(WindowKind.Other, parseUsageReport("""{"providers":[{"provider":"x","windows":[
            {"label":"5-hour","utilization_pct":10}]}]}""").providers.single().windows.single().kind)
    }

    @Test fun `monthly-only provider titles its window from the duration and still fills a chip`() {
        val provider = parseUsageReport("""{"providers":[{"provider":"cursor","windows":[
            {"label":"","utilization_pct":40,"window_seconds":2592000}]}]}""").providers.single()
        assertTrue(provider.headline.isEmpty())
        assertEquals("30-day", provider.windows.single().title)
        assertEquals("30d", provider.windows.single().short)
    }

    @Test fun `more than two providers sort Codex, Claude, then alphabetically and failures are listed once`() {
        val report = parseUsageReport("""{"providers":[
            {"provider":"gemini","windows":[]},{"provider":"claude","windows":[]},
            {"provider":"antigravity","windows":[]},{"provider":"codex","windows":[]}],
          "unavailable":[{"provider":"copilot","message":"Needs authorization"},{"provider":"copilot","message":"dup"},
            {"provider":"codex","message":"stale error for a provider that did report"}]}""")
        assertEquals(listOf("codex", "claude", "antigravity", "gemini"), report.providers.map { it.id })
        assertEquals(listOf(ProviderIssue("copilot", "Needs authorization")), report.unavailable)
    }

    @Test fun `empty, partial and malformed reports degrade without failing`() {
        assertTrue(parseUsageReport("""{"providers":[],"unavailable":[]}""").providers.isEmpty())
        assertTrue(parseUsageReport("{}").providers.isEmpty())
        val partial = parseUsageReport("""{"providers":[{"provider":"claude","windows":[
            {"label":"5-hour","window_seconds":18000},
            {"label":"weekly","utilization_pct":"oops","window_seconds":604800},
            {"label":"weekly","utilization_pct":140,"window_seconds":604800,"reset_at":"0001-01-01T00:00:00Z"}]},
            {"windows":[]}, 7]}""")
        val window = partial.providers.single().windows.single()
        assertEquals(0.0, window.remainingPercent, 0.0)
        assertNull("A zero time is not a real reset", window.resetAt)
        assertThrows(Exception::class.java) { parseUsageReport("<html>Device authentication required.</html>") }
    }

    @Test fun `remaining percent can come straight from the plugin`() {
        val window = parseUsageReport("""{"providers":[{"provider_id":"codex","windows":[
            {"window_name":"weekly","remaining_percentage":12.5,"window_seconds":604800}]}]}""").providers.single().windows.single()
        assertEquals(12.5, window.remainingPercent, 0.0)
    }

    @Test fun `countdown is compact and rounds up so a reset never reads as past too early`() {
        val now = Instant.parse("2026-11-04T04:00:00Z")
        assertEquals("1m", countdown(now.plusSeconds(5), now))
        assertEquals("42m", countdown(now.plusSeconds(42 * 60), now))
        assertEquals("4h 51m", countdown(now.plusSeconds(4 * 3600 + 50 * 60 + 1), now))
        assertEquals("5h", countdown(now.plusSeconds(5 * 3600), now))
        assertEquals("1d 8h", countdown(now.plusSeconds(32 * 3600 + 6 * 60), now))
        assertEquals("5d", countdown(now.plusSeconds(5 * 86400), now))
        assertNull(countdown(now, now))
        assertNull(countdown(now.minusSeconds(1), now))
    }

    @Test fun `exact reset time is shown in the phone's zone`() {
        val reset = Instant.parse("2026-11-05T12:06:07Z")
        assertEquals("Thu, Nov 5, 7:06 AM", localResetTime(reset, ZoneId.of("America/Toronto"), Locale.US))
        assertEquals("Thu, Nov 5, 5:51 PM", localResetTime(reset, ZoneId.of("Asia/Kathmandu"), Locale.US))
        assertEquals("Thu, Nov 5, 12:06 PM", localResetTime(reset, ZoneId.of("UTC"), Locale.US))
    }

    @Test fun `percent never rounds a sliver to a misleading whole`() {
        assertEquals("0%", percent(0.0)); assertEquals("<1%", percent(0.4)); assertEquals("69%", percent(69.0))
        assertEquals(">99%", percent(99.6)); assertEquals("100%", percent(100.0))
    }

    @Test fun `report is stale after two polls or thirty minutes by either clock`() {
        val report = parseUsageReport(live)
        val generated = Instant.parse("2026-11-04T04:09:19Z")
        assertFalse(usageStale(report, generated.toEpochMilli(), generated.plusSeconds(29 * 60)))
        assertTrue(usageStale(report, generated.toEpochMilli(), generated.plusSeconds(31 * 60)))
        // Fresh on the phone but an old server snapshot is still stale.
        assertTrue(usageStale(report, generated.plusSeconds(40 * 60).toEpochMilli(), generated.plusSeconds(41 * 60)))
        assertTrue(usageStale(report.copy(generatedAt = null), null, generated))
    }
}
