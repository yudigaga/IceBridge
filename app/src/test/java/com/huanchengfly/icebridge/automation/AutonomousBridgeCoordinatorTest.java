package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator.Kind;
import static com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator.Outcome;
import static com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator.State;
import static org.junit.Assert.*;

public class AutonomousBridgeCoordinatorTest {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final String TARGET = "com.example.target";
    private static final String OTHER = "com.example.other";
    private static final String SOURCE = "com.tencent.mm";
    private static final State FROZEN = State.known(true, 3, false, false, false);
    private static final State READY = State.known(true, 0, true, false, false);
    private static final State ABSENT = State.known(false, 0, false, false, false);

    @Test public void prepareReusesSourcePriorityAndConversionWithoutMutationOrDeduplication() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(0, OTHER, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "prepared", true, true);
        List<LinkRuleEntry> rules = Arrays.asList(rule("*", OTHER, "manual", "none"),
                rule(SOURCE, TARGET, "manual", "fixed_deep_link"));
        AutonomousBridgeCoordinator.Plan first = f.worker.prepare(request, rules);
        assertNotNull(first);
        assertEquals(TARGET, first.targetPackage);
        assertEquals("fixed_deep_link", first.conversion);
        assertNotNull(f.worker.prepare(request, rules));
        assertSame(FROZEN, f.packages.states.get("0:" + TARGET));
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(0, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
        assertTrue(f.progress.isEmpty());
        // Preparing must not reserve the request in the worker's successful-transaction map.
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules).outcome);
        f.packages.put(0, TARGET, FROZEN);
        f.now = 2_000;
        assertNotNull("Preflight must not consult worker-owned deduplication", f.worker.prepare(request, rules));
        f.now = 1_000;
        // Nor may preparation prune an entry using the later preflight time.
        assertEquals(Outcome.DUPLICATE, f.worker.execute(request, rules).outcome);
        assertEquals(1, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void prepareRequiresWebAutoOpenKnownUserAndAcceptsEnabledRulesWithLegacyFrozenOnly() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        assertNotNull(f.worker.prepare(request(Kind.WEB_RULE, 0, SOURCE, "legacy-frozen-only", true, true), rules()));
        AutonomousBridgeCoordinator.Request<Object> allowEnabled = new AutonomousBridgeCoordinator.Request<>(
                Kind.WEB_RULE, 0, SOURCE, "example.com", null, "enabled", new Object(), true, true, false);
        AutonomousBridgeCoordinator.Plan plan = f.worker.prepare(allowEnabled, rules());
        assertNotNull(plan);
        assertEquals(TARGET, plan.targetPackage);
        f.packages.reads.clear();
        assertNull(f.worker.prepare(request(Kind.EXPLICIT, 0, SOURCE, "explicit", true, true), rules()));
        assertNull(f.worker.prepare(request(Kind.THAW_ONLY, 0, SOURCE, "thaw-only", true, true), rules()));
        assertNull(f.worker.prepare(request(Kind.WEB_RULE, 0, SOURCE, "auto-off", true, false), rules()));
        assertNull(f.worker.prepare(request(Kind.WEB_RULE, -1, SOURCE, "unknown-user", true, true), rules()));
        assertTrue("Rejected request kinds and settings must not query package state", f.packages.reads.isEmpty());
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(0, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void prepareDoesNotClaimAmbiguousOrUnsupportedTargets() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(0, OTHER, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "unclaimed", true, true);
        assertNull(f.worker.prepare(request, Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", OTHER, "online", "none"))));
        assertNull(f.worker.prepare(request, Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", TARGET, "online", "fixed_deep_link"))));
        f.packages.enable = false;
        assertNull(f.worker.prepare(request, rules()));
        f.packages.enable = true;
        f.packages.unhide = false;
        f.packages.put(0, TARGET, State.known(true, 0, true, true, false));
        assertNull(f.worker.prepare(request, rules()));
        f.packages.unhide = true;
        assertNotNull("A hidden target is claimable when unhide is supported", f.worker.prepare(request, rules()));
        f.packages.put(0, TARGET, State.known(true, 0, true, false, true));
        assertNull(f.worker.prepare(request, rules()));
        f.packages.put(0, TARGET, State.unknown("read-failed"));
        assertNull(f.worker.prepare(request, rules()));
        f.packages.put(0, SELF, FROZEN);
        assertNull(f.worker.prepare(request, Collections.singletonList(rule("*", SELF, "manual", "none"))));
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(0, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
        assertTrue(f.progress.isEmpty());
    }

    @Test public void sourceSpecificConversionAndLiveUserStateReachLauncher() {
        Fixture f = new Fixture();
        f.packages.put(10, TARGET, FROZEN);
        f.packages.put(10, OTHER, FROZEN);
        List<LinkRuleEntry> rules = Arrays.asList(rule(SOURCE, TARGET, "manual", "fixed_deep_link"),
                rule("com.tencent.mobileqq", OTHER, "manual", "none"));
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 10, SOURCE, "a", false, true);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules).outcome);
        assertEquals(Collections.singletonList("10:" + TARGET), f.packages.thaws);
        assertEquals(TARGET, f.launcher.target);
        assertEquals("fixed_deep_link", f.launcher.conversion);
        assertSame(request, f.launcher.launched);
        assertEquals(10, f.launcher.launched.userId);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void enabledRuleUsesModuleRouteWithoutThawingOrReplayingOriginal() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.WEB_RULE, 0, SOURCE,
                "a", true, true), rules()).outcome);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(0, f.launcher.fallbacks);
        assertEquals(1, f.launcher.launches);
        assertEquals(TARGET, f.launcher.target);
    }

    @Test public void enabledTargetNeedsNoThawCapabilityAndUsesMatchingSourceConversion() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        f.packages.enable = false;
        f.packages.unhide = false;
        List<LinkRuleEntry> rules = Collections.singletonList(
                rule(SOURCE, TARGET, "manual", "fixed_deep_link"));
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "enabled-conversion", true, true);
        assertTrue(request.onlyFrozen); // An old persisted preference must not suppress the rule.
        assertNotNull(f.worker.prepare(request, rules));
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules).outcome);
        assertSame(request, f.launcher.launched);
        assertEquals("fixed_deep_link", f.launcher.conversion);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(Collections.singletonList("OPENING:" + TARGET + ":0:0"), f.progress);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void externalThawAfterPreflightDoesNotReturnOwnedClickToOriginalBrowser() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "external-thaw", true, true);
        assertNotNull(f.worker.prepare(request, rules()));
        f.packages.put(0, TARGET, READY);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(1, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void failedEnabledRuleLaunchRestoresOwnedOriginalExactlyOnceWithoutThawing() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        f.launcher.failLaunch = true;
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "enabled-failure", true, true);
        AutonomousBridgeCoordinator.Result result = f.worker.execute(request, rules());
        assertEquals(Outcome.PASSTHROUGH, result.outcome);
        assertTrue(result.originalReplayed);
        assertSame(request, f.launcher.fallback);
        assertEquals(1, f.launcher.launches);
        assertEquals(1, f.launcher.fallbacks);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void targetMustBeInstalledForTheRequestedUser() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(10, TARGET, ABSENT);
        assertEquals("not-installed-for-user", f.worker.execute(request(Kind.EXPLICIT, 10,
                SOURCE, "a", true, true), rules()).reason);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(Collections.singletonList("10:" + TARGET), f.packages.reads);
    }

    @Test public void missingUserAndUnmatchedSourceNeverReadPackageState() {
        Fixture f = new Fixture();
        assertEquals("user-unknown", f.worker.execute(request(Kind.WEB_RULE, -1, SOURCE,
                "a", false, true), rules()).reason);
        assertEquals("no-matching-rule", f.worker.execute(request(Kind.WEB_RULE, 0,
                "com.tencent.mobileqq", "b", false, true),
                Collections.singletonList(rule(SOURCE, TARGET, "manual", "none"))).reason);
        assertTrue(f.packages.reads.isEmpty());
    }

    @Test public void unknownHiddenStateAndSuspensionAreNeverMutated() {
        for (State state : Arrays.asList(new State(true, true, 3, true, false,
                false, false, false, true, null), State.known(true, 3, false, false, true))) {
            Fixture f = new Fixture();
            f.packages.put(0, TARGET, state);
            assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.EXPLICIT, 0,
                    SOURCE, "a", true, true), rules()).outcome);
            assertTrue(f.packages.thaws.isEmpty());
            assertEquals(0, f.launcher.launches);
            assertEquals(1, f.launcher.fallbacks);
        }
    }

    @Test public void disabledAndHiddenRequireBothCapabilitiesBeforeAnyMutation() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, State.known(true, 3, false, true, false));
        f.packages.unhide = false;
        assertEquals("freeze-method-unsupported", f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "a", true, true), rules()).reason);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void hiddenOnlyPackageCanBeUnhiddenWithoutEnableCapability() {
        Fixture f = new Fixture();
        f.packages.enable = false;
        f.packages.put(0, TARGET, State.known(true, 0, true, true, false));
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0, SOURCE,
                "a", false, true), rules()).outcome);
        assertEquals(Collections.singletonList("0:" + TARGET), f.packages.thaws);
    }

    @Test public void readbackMustConfirmInstalledEnabledUnhiddenAndUnsuspended() {
        for (State state : Arrays.asList(FROZEN, ABSENT, State.known(true, 1, true, true, false),
                State.known(true, 1, true, false, true), State.unknown("read-failed"),
                State.known(true, 0, false, false, false))) {
            Fixture f = new Fixture();
            f.packages.put(0, TARGET, FROZEN);
            f.packages.after = state;
            assertEquals("thaw-readback-failed", f.worker.execute(request(Kind.EXPLICIT, 0,
                    SOURCE, "a", true, true), rules()).reason);
            assertEquals(0, f.launcher.launches);
            assertEquals(1, f.launcher.fallbacks);
        }
    }

    @Test public void twoFrozenTargetsAtEqualPriorityPreserveOriginal() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(0, OTHER, FROZEN);
        assertEquals("no-unambiguous-rule", f.worker.execute(request(Kind.WEB_RULE,
                0, SOURCE, "a", true, true), Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", OTHER, "online", "none"))).reason);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(1, f.launcher.fallbacks);
    }

    @Test public void sharedEnabledAndFrozenTargetsRemainAmbiguousRatherThanDependingOnFreezeState() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(0, OTHER, READY);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.WEB_RULE, 0, SOURCE,
                "a", false, true), Arrays.asList(rule("*", OTHER, "online", "none"),
                rule("*", TARGET, "online", "none"))).outcome);
        assertNull(f.launcher.target);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void installedManualRuleShadowsFrozenOnlineRuleWithoutReadingLowerPriority() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        f.packages.put(0, OTHER, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "a", false, true), Arrays.asList(rule("*", OTHER, "online", "none"),
                rule("*", TARGET, "manual", "none"))).outcome);
        assertEquals(Collections.singletonList("0:" + TARGET), f.packages.reads);
        assertEquals(TARGET, f.launcher.target);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void uninstalledManualRuleDoesNotShadowInstalledOnlineRule() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, ABSENT);
        f.packages.put(0, OTHER, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.WEB_RULE, 0, SOURCE,
                "a", false, true), Arrays.asList(rule("*", TARGET, "manual", "none"),
                rule("*", OTHER, "online", "none"))).outcome);
        assertEquals(OTHER, f.launcher.target);
    }

    @Test public void conflictingConversionsAndSuspendedSharedTargetAreNotClaimed() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        assertEquals("ambiguous-conversion", f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "a", true, true), Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", TARGET, "online", "fixed_deep_link"))).reason);
        f.packages.put(0, OTHER, State.known(true, 0, true, false, true));
        assertEquals("suspended-unsupported", f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "b", true, true), Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", OTHER, "online", "none"))).reason);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void neverThawsOrLaunchesIceBridgeItself() {
        Fixture f = new Fixture();
        f.packages.put(0, SELF, FROZEN);
        assertEquals("self-thaw-prohibited", f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "a", true, true), Collections.singletonList(rule("*", SELF,
                "manual", "none"))).reason);
        assertTrue(f.packages.thaws.isEmpty());
        assertEquals(0, f.launcher.launches);
    }

    @Test public void explicitAndPaymentThawOnlyKeepTheOriginalPayload() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.EXPLICIT, 0,
                SOURCE, "a", false, true);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        assertSame(request.payload, f.launcher.launched.payload);
        assertEquals("none", f.launcher.conversion);
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.THAWED, f.worker.execute(request(Kind.THAW_ONLY, 0,
                SOURCE, "b", false, true), rules()).outcome);
        assertEquals(1, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void autoOpenDisabledThawsWithoutCreatingAnotherActivity() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.THAWED, f.worker.execute(request(Kind.EXPLICIT, 0, SOURCE,
                "a", false, false), rules()).outcome);
        assertEquals(0, f.launcher.launches);
    }

    @Test public void onlyFrozenDisabledCanRouteUniqueEnabledWebTargetWithoutThawing() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        AutonomousBridgeCoordinator.Request<Object> request = new AutonomousBridgeCoordinator.Request<>(
                Kind.WEB_RULE, 0, SOURCE, "example.com", null, "a", new Object(), false, true, false);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        assertEquals(TARGET, f.launcher.target);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void onlyFrozenDisabledStillPreservesEnabledExplicitOriginalAndRejectsAmbiguity() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        AutonomousBridgeCoordinator.Request<Object> explicit = new AutonomousBridgeCoordinator.Request<>(
                Kind.EXPLICIT, 0, SOURCE, null, TARGET, "a", new Object(), false, true, false);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(explicit, rules()).outcome);
        f.packages.put(0, OTHER, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> web = new AutonomousBridgeCoordinator.Request<>(
                Kind.WEB_RULE, 0, SOURCE, "example.com", null, "b", new Object(), false, true, false);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(web, Arrays.asList(
                rule("*", TARGET, "online", "none"), rule("*", OTHER, "online", "none"))).outcome);
        assertEquals(0, f.launcher.launches);
        assertTrue(f.packages.thaws.isEmpty());
    }

    @Test public void launchFailureReplaysOwnedOriginalExactlyOnce() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.launcher.failLaunch = true;
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.EXPLICIT, 0,
                SOURCE, "a", true, true);
        AutonomousBridgeCoordinator.Result result = f.worker.execute(request, rules());
        assertEquals(Outcome.PASSTHROUGH, result.outcome);
        assertEquals("launch-failed:Exception", result.reason);
        assertEquals(TARGET, result.targetPackage);
        assertTrue(result.originalReplayed);
        assertSame(request, f.launcher.fallback);
        assertEquals(1, f.launcher.fallbacks);
    }

    @Test public void packageMutationExceptionKeepsKnownTargetWithoutClaimingALaunchWasAttempted() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.throwOnThaw = true;
        AutonomousBridgeCoordinator.Result result = f.worker.execute(request(Kind.EXPLICIT,
                0, SOURCE, "a", false, true), rules());
        assertEquals("worker-IllegalStateException", result.reason);
        assertEquals(TARGET, result.targetPackage);
        assertEquals(0, f.launcher.launches);
    }

    @Test public void observationsNeverReplayTheOriginalOnFailure() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.failure = "SecurityException";
        assertEquals("thaw-SecurityException", f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "a", false, true), rules()).reason);
        assertEquals(0, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
    }

    @Test public void deduplicationDoesNotExtendOnDuplicateAndRetainsUserAndSourceIdentity() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> first = request(Kind.EXPLICIT, 0,
                SOURCE, "a", false, true);
        assertEquals(Outcome.ROUTED, f.worker.execute(first, rules()).outcome);
        f.now = 1_000;
        assertEquals(Outcome.DUPLICATE, f.worker.execute(first, rules()).outcome);
        f.packages.put(10, TARGET, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 10,
                SOURCE, "a", false, true), rules()).outcome);
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0,
                "com.tencent.mobileqq", "a", false, true), rules()).outcome);
        f.now = 1_500;
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(first, rules()).outcome);
    }

    @Test public void deduplicationCapacityIsBoundedAndClockRollbackDoesNotBlockNewRequest() {
        Fixture f = new Fixture();
        for (int i = 0; i < 257; i++) {
            f.packages.put(0, TARGET, FROZEN);
            assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0,
                    SOURCE, "key-" + i, false, true), rules()).outcome);
        }
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "key-0", false, true), rules()).outcome);
        f.now = -1;
        f.packages.put(0, TARGET, FROZEN);
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "key-0", false, true), rules()).outcome);
    }

    @Test public void progressReportsTheSelectedTargetBeforeThawAndBeforeOpening() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "progress", false, true);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        assertEquals(Arrays.asList("THAWING:" + TARGET + ":0:0", "OPENING:" + TARGET + ":1:0"),
                f.progress);
        assertSame(request, f.observedRequest);
    }

    @Test public void thawOnlyAndAutoOpenDisabledDoNotClaimToBeOpening() {
        for (Kind kind : Arrays.asList(Kind.EXPLICIT, Kind.THAW_ONLY)) {
            Fixture f = new Fixture();
            f.packages.put(0, TARGET, FROZEN);
            assertEquals(Outcome.THAWED, f.worker.execute(request(kind, 0, SOURCE,
                    "progress", false, false), rules()).outcome);
            assertEquals(Collections.singletonList("THAWING:" + TARGET + ":0:0"), f.progress);
        }
    }

    @Test public void alreadyLaunchableWebTargetReportsOnlyOpening() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, READY);
        AutonomousBridgeCoordinator.Request<Object> request = new AutonomousBridgeCoordinator.Request<>(
                Kind.WEB_RULE, 0, SOURCE, "example.com", null, "progress", new Object(), false, true, false);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        assertEquals(Collections.singletonList("OPENING:" + TARGET + ":0:0"), f.progress);
    }

    @Test public void failedThawAndFailedReadbackNeverReportOpening() {
        for (boolean failThaw : Arrays.asList(true, false)) {
            Fixture f = new Fixture();
            f.packages.put(0, TARGET, FROZEN);
            if (failThaw) f.packages.failure = "denied";
            else f.packages.after = FROZEN;
            assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.EXPLICIT, 0,
                    SOURCE, "progress", false, true), rules()).outcome);
            assertEquals(Collections.singletonList("THAWING:" + TARGET + ":0:0"), f.progress);
            assertEquals(0, f.launcher.launches);
        }
    }

    @Test public void rejectedSelectionsAndUnsupportedThawNeverShowProgress() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.packages.put(0, OTHER, FROZEN);
        f.packages.put(0, SELF, FROZEN);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "ambiguous", false, true), Arrays.asList(rule("*", TARGET, "online", "none"),
                rule("*", OTHER, "online", "none"))).outcome);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "self", false, true), Collections.singletonList(
                rule("*", SELF, "manual", "none"))).outcome);
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.WEB_RULE, 0,
                SOURCE, "unmatched", false, true), Collections.emptyList()).outcome);
        f.packages.put(0, TARGET, State.known(true, 3, false, false, true));
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "suspended", false, true), rules()).outcome);
        f.packages.put(0, TARGET, FROZEN);
        f.packages.enable = false;
        assertEquals(Outcome.PASSTHROUGH, f.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "unsupported", false, true), rules()).outcome);
        assertTrue(f.progress.isEmpty());
    }

    @Test public void successfulDuplicateDoesNotRepeatProgress() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        AutonomousBridgeCoordinator.Request<Object> request = request(Kind.WEB_RULE, 0,
                SOURCE, "progress", false, true);
        assertEquals(Outcome.ROUTED, f.worker.execute(request, rules()).outcome);
        int before = f.progress.size();
        assertEquals(Outcome.DUPLICATE, f.worker.execute(request, rules()).outcome);
        assertEquals(2, before);
        assertEquals(before, f.progress.size());
    }

    @Test public void observerExceptionsDoNotChangeRoutingOrFallback() {
        Fixture f = new Fixture();
        f.packages.put(0, TARGET, FROZEN);
        f.failProgress = true;
        assertEquals(Outcome.ROUTED, f.worker.execute(request(Kind.EXPLICIT, 0, SOURCE,
                "progress", true, true), rules()).outcome);
        assertEquals(2, f.progress.size());
        assertEquals(1, f.launcher.launches);
        assertEquals(0, f.launcher.fallbacks);
        Fixture failed = new Fixture();
        failed.packages.put(0, TARGET, FROZEN);
        failed.launcher.failLaunch = true;
        failed.failProgress = true;
        assertEquals(Outcome.PASSTHROUGH, failed.worker.execute(request(Kind.EXPLICIT, 0,
                SOURCE, "progress", true, true), rules()).outcome);
        assertEquals(1, failed.launcher.fallbacks);
    }

    private static List<LinkRuleEntry> rules() {
        return Collections.singletonList(rule("*", TARGET, "online", "none"));
    }

    private static LinkRuleEntry rule(String source, String target, String origin, String conversion) {
        return new LinkRuleEntry(source, "example.com", target, origin, conversion, "");
    }

    private static AutonomousBridgeCoordinator.Request<Object> request(Kind kind, int user,
            String source, String fingerprint, boolean ownsOriginal, boolean autoOpen) {
        return new AutonomousBridgeCoordinator.Request<>(kind, user, source, "example.com",
                TARGET, fingerprint, new Object(), ownsOriginal, autoOpen);
    }

    private static final class Fixture {
        long now;
        final Packages packages = new Packages();
        final Launches launcher = new Launches();
        final List<String> progress = new ArrayList<>();
        AutonomousBridgeCoordinator.Request<Object> observedRequest;
        boolean failProgress;
        final AutonomousBridgeCoordinator<Object> worker = new AutonomousBridgeCoordinator<>(
                SELF, packages, launcher, () -> now, (request, target, stage) -> {
                    observedRequest = request;
                    progress.add(stage + ":" + target + ":" + packages.thaws.size() + ":" + launcher.launches);
                    if (failProgress) throw new IllegalStateException("progress-failed");
                });
    }

    private static final class Packages implements AutonomousBridgeCoordinator.PackageAccess {
        final Map<String, State> states = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        final List<String> thaws = new ArrayList<>();
        State after = READY;
        boolean enable = true;
        boolean unhide = true;
        boolean throwOnThaw;
        String failure;
        void put(int user, String target, State state) { states.put(user + ":" + target, state); }
        @Override public State read(String target, int user) {
            String key = user + ":" + target;
            reads.add(key);
            return states.containsKey(key) ? states.get(key) : State.unknown("no-state");
        }
        @Override public AutonomousBridgeCoordinator.Capabilities capabilities() {
            return new AutonomousBridgeCoordinator.Capabilities(enable, unhide, "fake");
        }
        @Override public String thaw(String target, int user, State before) {
            if (throwOnThaw) throw new IllegalStateException("package-mutation-failed");
            thaws.add(user + ":" + target);
            if (failure == null) put(user, target, after);
            return failure;
        }
    }

    private static final class Launches implements AutonomousBridgeCoordinator.Launcher<Object> {
        int launches;
        int fallbacks;
        String target;
        String conversion;
        boolean failLaunch;
        AutonomousBridgeCoordinator.Request<Object> launched;
        AutonomousBridgeCoordinator.Request<Object> fallback;
        @Override public void launch(AutonomousBridgeCoordinator.Request<Object> request,
                                     String targetPackage, String conversion) throws Exception {
            launches++;
            if (failLaunch) throw new Exception("launch-rejected");
            launched = request;
            target = targetPackage;
            this.conversion = conversion;
        }
        @Override public void fallback(AutonomousBridgeCoordinator.Request<Object> request) {
            fallbacks++;
            fallback = request;
        }
    }
}
