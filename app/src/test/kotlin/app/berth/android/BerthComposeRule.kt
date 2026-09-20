package app.berth.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

/**
 * The compose rule for the app's tests: [createComposeRule] with the composition, its effects and the
 * Recomposer's own loop on a `StandardTestDispatcher`, so no part of the composition ever runs on a
 * thread other than the test's.
 *
 * `createComposeRule()` puts them on an `UnconfinedTestDispatcher`, which resumes a continuation on
 * whatever thread resumes it. The `collectAsState` of a flow the SessionManager writes from its own
 * dispatcher is such a continuation: the emission resumes the collector on the manager's worker, the
 * worker writes the State there, and the test's interceptor then sends the apply notifications from that
 * thread, which wakes the Recomposer's loop on it too; woken with no frame pending, the loop records the
 * modification on the composition at once. A modification recorded while the test thread is between a
 * composition's `applyChanges` and its `applyLateChanges` drains immediately (the sentinel that defers
 * one recorded during composition was cleared at the end of `applyChanges`), and a scope whose anchor the
 * slot table does not own yet, which is every scope inside movable content still awaiting its late
 * apply, answers `IGNORED`; the change is consumed and the scope is never recomposed for it. Navigation3
 * wraps every entry's content in `movableContentOf`, so the Stage is such content on the first
 * composition and on each move between scenes. The unconfined start of the effect's own collect, inside
 * `applyChanges`, can wake the loop on the test thread before the late apply with the same result.
 * SecurityScreenshotTest's "an edit in progress survives the lock" met it 3 of 20 runs (4 of 20 across
 * the suite): the strip had its three tabs and the manager its active tab while the Stage showed no tab
 * and the overflow the tabless rows, and reopening the menu changed nothing, since the stale scope was
 * never marked; switching tabs, a further write, did.
 *
 * On a `StandardTestDispatcher` every resumption is queued on the test scheduler and run by the test
 * thread in `waitForIdle`, after `setContent` has applied both its change lists, which is the order the
 * phone keeps by running all of it on the main thread. Nothing about the app changes. The rule is built
 * with ui-test's own switch for this (`useStandardTestDispatcherForComposition`), which also has the
 * idling resource run the scheduler's queue before it answers idle, so an emission that arrived while
 * the test thread was elsewhere is in the composition by the time `waitForIdle` returns; a
 * `StandardTestDispatcher` placed in the effect context alone would be honoured as the composition's
 * dispatcher but not pumped, and `waitForIdle` would answer idle over the queued resumption. The switch
 * is on a constructor that is Kotlin-internal in 1.12, reached from [StandardDispatcherComposeRule] in
 * Java, which says how and until when.
 *
 * The rule launches [ComponentActivity] as `createComposeRule` does, so [ComposeHostRule] still declares
 * it for the release variant, ordered before this rule.
 */
fun createBerthComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
    StandardDispatcherComposeRule.create()
