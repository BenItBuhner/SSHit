package app.berth.android;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import kotlin.coroutines.EmptyCoroutineContext;

/**
 * Builds the app's compose rule with the composition on a {@code StandardTestDispatcher}; BerthComposeRule.kt
 * says why and is the one place tests call.
 *
 * <p>It is written in Java because the constructor that turns the standard dispatcher on
 * ({@code useStandardTestDispatcherForComposition}) is Kotlin-internal in ui-test-junit4 1.12: public in the
 * bytecode, as every constructor Kotlin emits is, and unmangled, since only internal functions get a
 * module suffix. Java has no notion of internal and calls it as any public constructor, checked at compile
 * time, with nothing suppressed in the Kotlin compiler. The flag does two things a dispatcher placed in the
 * effect context does not: ComposeIdlingResource.isIdleNow runs the scheduler's queued tasks before it looks
 * for work, so a resumption another thread queued (a flow's emission reaching collectAsState) is run and its
 * state written before waitForIdle can answer idle, and the main clock's advanceTimeUntil does the same
 * between checks. The day the standard dispatcher is the default (the constructor's own TODO, b/369324208)
 * this stops compiling, and {@code createComposeRule()} is the whole of the rule again.
 */
final class StandardDispatcherComposeRule {
    private StandardDispatcherComposeRule() {}

    static AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> create() {
        ActivityScenarioRule<ComponentActivity> activityRule = new ActivityScenarioRule<>(ComponentActivity.class);
        return new AndroidComposeTestRule<>(
                activityRule,
                EmptyCoroutineContext.INSTANCE,
                true,
                rule -> {
                    ComponentActivity[] launched = new ComponentActivity[1];
                    rule.getScenario().onActivity(activity -> launched[0] = activity);
                    if (launched[0] == null) {
                        throw new IllegalStateException("the test activity was not launched");
                    }
                    return launched[0];
                });
    }
}
