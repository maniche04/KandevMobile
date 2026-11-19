package ca.mslab.forge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.mslab.forge.data.*
import ca.mslab.forge.ui.theme.ForgeTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageStripTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.parse("2026-11-04T04:10:00Z")
    private val report = parseUsageReport("""
        {"generated_at":"2026-11-04T04:09:19Z","poll_minutes":10,"providers":[
         {"provider":"claude","plan":"Claude Max 5x","windows":[
           {"label":"5-hour","utilization_pct":2,"reset_at":"2026-11-04T09:00:00Z","window_seconds":18000},
           {"label":"weekly","utilization_pct":0,"reset_at":"2026-11-09T02:00:00Z","window_seconds":604800},
           {"label":"Fable only","utilization_pct":0,"reset_at":"2026-11-09T02:00:00Z","scoped":true,"window_seconds":604800}],
          "fetched_at":"2026-11-04T04:09:19Z"},
         {"provider":"codex","plan":"prolite","windows":[
           {"label":"weekly","utilization_pct":31,"reset_at":"2026-11-05T12:06:07Z","window_seconds":604800}],
          "fetched_at":"2026-11-04T04:09:19Z"},
         {"provider":"cursor","windows":[{"label":"Total","utilization_pct":93,"window_seconds":2592000}]},
         {"provider":"gemini","windows":[{"label":"Pro","utilization_pct":80,"window_seconds":86400}]}],
         "unavailable":[{"provider":"copilot","message":"Copilot needs CodexBar authorization."}]}
    """)
    private val fresh = UsageView(report, now.toEpochMilli(), null, false)

    private fun show(view: UsageView, onRefresh: () -> Unit = {}) = rule.setContent {
        ForgeTheme(darkTheme = false) {
            Column(Modifier.fillMaxSize()) {
                UsageStrip(view, onRefresh, logos = { null }, now = now)
                LazyColumn(Modifier.weight(1f)) { items(40) { Text("Task $it", Modifier.testTag("task-$it")) } }
            }
        }
    }

    @Test fun stripIsOneCompactLineAboveAUsableTaskList() {
        show(fresh)
        val strip = rule.onNodeWithTag("usage-strip").assertIsDisplayed().fetchSemanticsNode()
        val height = with(rule.density) { strip.size.height.toDp() }
        assertTrue("strip is $height", height in 32.dp..44.dp)
        rule.onNodeWithTag("usage-chip-codex").assertIsDisplayed()
        rule.onNodeWithTag("task-0").assertIsDisplayed()
        rule.onNodeWithTag("task-10").assertIsDisplayed()
        // More than two providers scroll sideways instead of wrapping onto a second line.
        rule.onNodeWithTag("usage-strip").performScrollToKey("gemini")
        rule.onNodeWithTag("usage-chip-gemini").assertIsDisplayed()
        assertEquals(strip.size.height, rule.onNodeWithTag("usage-strip").fetchSemanticsNode().size.height)
    }

    @Test fun chipsShowOnlyReportedWindowsWithRemainingAndReset() {
        show(fresh)
        // Codex reports no 5-hour window here: only the weekly one appears.
        rule.onNodeWithTag("usage-chip-codex")
            .assertContentDescriptionEquals("Codex: Weekly 69% left, resets in 1d 7h. Opens usage details.")
        rule.onNodeWithTag("usage-chip-claude")
            .assertContentDescriptionEquals("Claude: 5-hour 98% left, resets in 4h 50m; Weekly 100% left, resets in 4d 21h. Opens usage details.")
        // Codex and Claude lead; the scoped "Fable only" window never takes a chip slot.
        rule.onNodeWithTag("usage-chip-codex").assertLeftPositionInRootIsEqualTo(12.dp)
        rule.onAllNodesWithText("Fabl", substring = true).assertCountEquals(0)
        // A provider with only a monthly window labels it by its duration.
        rule.onNodeWithTag("usage-strip").performScrollToKey("cursor")
        rule.onNodeWithTag("usage-chip-cursor").assertContentDescriptionEquals("Cursor: Total 7% left. Opens usage details.")
        rule.onNodeWithText("30d").assertIsDisplayed()
    }

    @Test fun tappingAChipOpensFullDetailsAndRefresh() {
        var refreshed = 0
        show(fresh) { refreshed++ }
        rule.onNodeWithTag("usage-chip-claude").performClick()
        rule.onNodeWithTag("usage-sheet").assertIsDisplayed()
        rule.onNodeWithText("Provider usage").assertIsDisplayed()
        rule.onNodeWithText("Checked just now").assertIsDisplayed()
        rule.onNodeWithText("Claude Max 5x").assertIsDisplayed()
        rule.onNodeWithText("98% left").assertIsDisplayed()
        rule.onNodeWithText("2% used").assertIsDisplayed()
        val local = localResetTime(Instant.parse("2026-11-04T09:00:00Z"), ZoneId.systemDefault(), Locale.getDefault())
        rule.onNodeWithText("Resets in 4h 50m · $local").assertIsDisplayed()
        rule.onNodeWithText("Fable only · weekly").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copilot needs CodexBar authorization.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Refresh").performScrollTo().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun nothingToShowLeavesNoStrip() {
        show(UsageView(null, null, UsageProblem.NotInstalled, false))
        rule.onNodeWithTag("usage-strip").assertDoesNotExist()
        rule.onNodeWithTag("task-0").assertIsDisplayed()
    }

    @Test fun emptyReportLeavesNoStrip() {
        show(UsageView(parseUsageReport("""{"providers":[],"unavailable":[]}"""), now.toEpochMilli(), null, false))
        rule.onNodeWithTag("usage-strip").assertDoesNotExist()
    }

    @Test fun staleOrFailedDataStaysVisibleWithASubtleHint() {
        val old = now.plusSeconds(3 * 3600)
        rule.setContent {
            ForgeTheme(darkTheme = true) {
                UsageStrip(UsageView(report, now.toEpochMilli(), UsageProblem.Offline, false), {}, { null }, now = old)
            }
        }
        rule.onNodeWithTag("usage-chip-claude").assertIsDisplayed()
        rule.onNodeWithTag("usage-strip").performScrollToKey("state")
        rule.onNodeWithContentDescription("Usage data is out of date").assertIsDisplayed().performClick()
        rule.onNodeWithText("Can't reach the server. Showing the last saved usage.").assertIsDisplayed()
        rule.onNodeWithText("Out of date", substring = true).assertIsDisplayed()
    }
}
