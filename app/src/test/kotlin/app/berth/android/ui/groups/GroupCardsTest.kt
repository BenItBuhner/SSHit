package app.berth.android.ui.groups

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What a group's card says of its tabs (spec C8), as the pure functions behind the card. */
class GroupCardsTest {
    private val now = 1_700_000_000_000L
    private val host = Host(id = "h", name = "prod-web", color = SwatchColor.COPPER, monogram = "PW", address = "10.0.0.12", user = "ben", createdAt = 0)

    private fun tab(id: String, state: SessionState = SessionState.DETACHED, attention: Boolean = false, liveAgo: Long? = TimeUnit.MINUTES.toMillis(4), title: String = id, order: Int = 0) =
        SessionRecord(id = id, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = state, title = title, needsAttention = attention, sortOrder = order, createdAt = 0, lastLiveAt = liveAgo?.let { now - it })

    @Test
    fun `the count line counts tabs and, after the dot, the ones that need the user`() {
        assertEquals("No tabs", GroupCards.countLine(emptyList()))
        assertEquals("1 tab", GroupCards.countLine(listOf(tab("a"))))
        assertEquals("4 tabs", GroupCards.countLine(List(4) { tab("t$it") }))
        assertEquals("2 tabs \u00B7 1 needs you", GroupCards.countLine(listOf(tab("a"), tab("b", attention = true))))
        assertEquals("3 tabs \u00B7 2 need you", GroupCards.countLine(listOf(tab("a", attention = true), tab("b", attention = true), tab("c"))))
    }

    @Test
    fun `a card lists three tabs at most, in strip order, the ones needing the user first`() {
        val tabs = listOf(tab("one", order = 0), tab("two", order = 1), tab("three", order = 2, attention = true), tab("four", order = 3))
        val lines = GroupCards.lines(tabs, now)
        assertEquals(GroupCards.SHOWN, lines.size)
        assertEquals("the tab that needs the user leads, the rest keep their order and the fourth is left to the count", listOf("three", "one", "two"), lines.map { it.title })
        assertEquals(listOf(true, false, false), lines.map { it.needsAttention })
        assertEquals("the strip's order stands when no tab needs the user", listOf("one", "two", "three"), GroupCards.lines(tabs.map { it.copy(needsAttention = false) }, now).map { it.title })
    }

    @Test
    fun `a line's mark is the age for a tab that is down and nothing for one that is up`() {
        val lines = GroupCards.lines(listOf(tab("live", SessionState.LIVE), tab("connecting", SessionState.CONNECTING, liveAgo = null), tab("down", liveAgo = TimeUnit.HOURS.toMillis(2))), now)
        assertNull("a Live tab shows its dot, not an age", lines[0].age)
        assertNull("a connecting tab too", lines[1].age)
        assertEquals("2h", lines[2].age)
        assertEquals("the moment behind the age comes along for the reader's longer form", now - TimeUnit.HOURS.toMillis(2), lines[2].lastLiveAt)
        assertNull("a tab that was never Live has no age", GroupCards.lines(listOf(tab("new", liveAgo = null)), now).single().age)
    }

    @Test
    fun `the short age is the largest unit that fits, now under a minute`() {
        assertNull(GroupCards.shortAge(null, now))
        assertEquals("now", GroupCards.shortAge(now - 30_000, now))
        assertEquals("a clock that ran backwards is now, not a negative", "now", GroupCards.shortAge(now + 5_000, now))
        assertEquals("1m", GroupCards.shortAge(now - TimeUnit.SECONDS.toMillis(60), now))
        assertEquals("4m", GroupCards.shortAge(now - TimeUnit.MINUTES.toMillis(4), now))
        assertEquals("59m", GroupCards.shortAge(now - TimeUnit.MINUTES.toMillis(59), now))
        assertEquals("1h", GroupCards.shortAge(now - TimeUnit.MINUTES.toMillis(60), now))
        assertEquals("23h", GroupCards.shortAge(now - TimeUnit.HOURS.toMillis(23), now))
        assertEquals("3d", GroupCards.shortAge(now - TimeUnit.DAYS.toMillis(3), now))
        assertEquals("6d", GroupCards.shortAge(now - TimeUnit.DAYS.toMillis(6), now))
        assertEquals("1w", GroupCards.shortAge(now - TimeUnit.DAYS.toMillis(7), now))
        assertEquals("2w", GroupCards.shortAge(now - TimeUnit.DAYS.toMillis(15), now))
    }
}
