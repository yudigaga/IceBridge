package com.huanchengfly.icebridge.android16;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SystemTransitPresenterTest {
    private static final String TARGET = "com.example.target";

    @Test public void intendedSmallWindowBoundsTravelWithCardUntilTargetAppears() {
        Fixture fixture = new Fixture();
        SmallWindowLayout placement = new SmallWindowLayout(55, 40, 100, 0);
        fixture.presenter.onStage(1, 0, "com.example.source", TARGET,
                SystemTransitPresenter.Stage.THAWING, 0xff7e57c2, "light", placement);
        fixture.ui.runReady();
        assertSame(placement, fixture.host.session.smallWindowLayout);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.presenter.onSubmitted(1);
        fixture.ui.runReady();
        assertSame("Legacy stage callbacks must preserve the transaction's placement", placement,
                fixture.host.session.smallWindowLayout);
        fixture.presenter.onTargetDrawn(1, 0, TARGET);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertNull("The next fullscreen target must use ordinary card size", fixture.host.session.smallWindowLayout);
    }

    @Test public void cardIsOptInAndDisabledStagesCreateNoUiWork() {
        Fixture fixture = new Fixture(false);
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        assertTrue(fixture.ui.pending.isEmpty());
        fixture.ui.runReady();
        assertEquals(0, fixture.registrations);
        assertEquals(0, fixture.host.visibilityChecks);
        assertEquals(0, fixture.host.shows);
        assertNull(fixture.host.session);
    }

    @Test public void enablingStartsOnlyANewTransaction() {
        Fixture fixture = new Fixture(false);
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.presenter.setEnabled(true);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(0, fixture.host.shows);
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(2, fixture.host.session.id);
        assertEquals(1, fixture.registrations);
    }

    @Test public void disablingClosesActiveCardWithoutWaitingForUiAndLateStageStaysClosed() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.ui.runReady();
        fixture.presenter.setEnabled(false);
        assertNotNull("Setting dispatch must not wait for window removal", fixture.host.session);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
        fixture.presenter.setEnabled(true);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(2, fixture.host.session.id);
        assertEquals("Enabling again reuses the protected receiver", 1, fixture.registrations);
    }

    @Test public void disableInvalidatesQueuedStageEvenWhenReenabledBeforeQueueDrains() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.presenter.setEnabled(false);
        fixture.stage(2, SystemTransitPresenter.Stage.THAWING);
        fixture.presenter.setEnabled(true);
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(0, fixture.registrations);
        assertEquals(0, fixture.host.shows);
        fixture.stage(3, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(3, fixture.host.session.id);
        assertEquals(1, fixture.host.shows);
    }

    @Test public void disablingDuringRegistrationCannotShowWindowAfterOptOut() {
        FakeDispatcher ui = new FakeDispatcher();
        FakeHost host = new FakeHost();
        SystemTransitPresenter presenter = new SystemTransitPresenter(ui, host, message -> { },
                () -> ui.now, owner -> owner.setEnabled(false));
        presenter.setEnabled(true);
        presenter.onStage(1, 0, TARGET, SystemTransitPresenter.Stage.THAWING, 0xff7e57c2, "light");
        ui.runReady();
        assertNull(host.session);
        assertEquals(0, host.visibilityChecks);
        assertEquals(0, host.shows);
    }

    @Test public void backendDoesNotWaitForWindowThread() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        assertNull(fixture.host.session);
        fixture.ui.runReady();
        assertEquals(TransitSessionPolicy.THAWING, fixture.host.session.stage);
    }

    @Test public void submissionKeepsCardUntilMatchingDraw() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.presenter.onSubmitted(1);
        fixture.ui.runReady();
        assertNotNull(fixture.host.session);
        fixture.presenter.onTargetDrawn(1, 0, TARGET);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void targetVisibilityOnWindowThreadClosesWithoutAnotherQueueTurn() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        fixture.ui.currentThread = true;
        fixture.presenter.onTargetDrawn(1, 0, TARGET);
        assertNull("A confirmed target surface must close the card in the current UI turn",
                fixture.host.session);
    }

    @Test public void homeCancellationDoesNotOvertakeAndReviveAnAlreadyQueuedStage() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.currentThread = true;
        fixture.presenter.cancelAll("homekey");
        fixture.ui.currentThread = false;
        fixture.ui.runReady();
        assertNull("Home must cancel a stage that was already queued before its callback",
                fixture.host.session);
    }

    @Test public void oldTimeoutAndDrawCannotRemoveNewerCard() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        fixture.ui.now = 10_000;
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        fixture.presenter.onTargetDrawn(1, 0, TARGET);
        fixture.presenter.finish(1, "old-launch-failed");
        fixture.ui.now = 12_000;
        fixture.ui.runReady();
        assertEquals(2, fixture.host.session.id);
        fixture.ui.now = 22_000;
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void stageUpdateDoesNotExtendDeadline() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.ui.runReady();
        fixture.ui.now = 10_000;
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(TransitSessionPolicy.OPENING, fixture.host.session.stage);
        fixture.ui.now = 12_000;
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void displayFailureIsPresentationOnlyAndDoesNotEscape() {
        Fixture fixture = new Fixture();
        fixture.host.failShow = true;
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
        fixture.host.failShow = false;
        fixture.stage(2, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertEquals(2, fixture.host.session.id);
    }

    @Test public void failedWindowRemovalAndLoggerNeverEscapeToBackend() {
        Fixture fixture = new Fixture();
        fixture.host.failDismiss = true;
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        fixture.presenter.finish(1, "launch-failed");
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void lockScreenSuppressesCardAndItsLaterStage() {
        Fixture fixture = new Fixture();
        fixture.host.visible = false;
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
        fixture.host.visible = true;
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void differentUserCannotLeaveOwnerCardVisible() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        fixture.presenter.onStage(2, 10, TARGET, SystemTransitPresenter.Stage.OPENING, 0, "light");
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void settingsCancellationClosesCurrentAndRejectsLatePhase() {
        Fixture fixture = new Fixture();
        fixture.stage(1, SystemTransitPresenter.Stage.THAWING);
        fixture.ui.runReady();
        fixture.presenter.cancelAll("settings-disabled");
        fixture.stage(1, SystemTransitPresenter.Stage.OPENING);
        fixture.ui.runReady();
        assertNull(fixture.host.session);
    }

    @Test public void earlyRegistrationFailureDoesNotPermanentlyDisableLaterSessions() {
        FakeDispatcher ui = new FakeDispatcher();
        FakeHost host = new FakeHost();
        int[] registrations = {0};
        SystemTransitPresenter presenter = new SystemTransitPresenter(ui, host, message -> { },
                () -> ui.now, owner -> {
                    registrations[0]++;
                    if (registrations[0] == 1) throw new IllegalStateException("AMS not ready");
                });
        presenter.setEnabled(true);
        // Construction and backend dispatch must not perform registration or wait on AMS.
        assertEquals(0, registrations[0]);
        presenter.onStage(1, 0, TARGET, SystemTransitPresenter.Stage.THAWING, 0xff7e57c2, "light");
        assertEquals(0, registrations[0]);
        ui.runReady();
        assertEquals(1, registrations[0]);
        assertNull("Never show without screen-off/user-switch cleanup registered", host.session);
        presenter.onStage(1, 0, TARGET, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
        ui.runReady();
        assertNull("The failed transaction stays closed", host.session);
        assertEquals(1, registrations[0]);
        presenter.onStage(2, 0, TARGET, SystemTransitPresenter.Stage.THAWING, 0xff7e57c2, "light");
        ui.runReady();
        assertEquals(2, registrations[0]);
        assertEquals(2, host.session.id);
        presenter.onStage(2, 0, TARGET, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
        presenter.onStage(3, 0, TARGET, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
        ui.runReady();
        assertEquals("Successful registration is reused", 2, registrations[0]);
        assertEquals(3, host.session.id);
    }

    private static final class Fixture {
        final FakeDispatcher ui = new FakeDispatcher();
        final FakeHost host = new FakeHost();
        int registrations;
        final SystemTransitPresenter presenter = new SystemTransitPresenter(ui, host,
                message -> { throw new IllegalStateException("test logger unavailable"); }, () -> ui.now,
                owner -> registrations++);
        Fixture() { this(true); }
        Fixture(boolean enabled) { presenter.setEnabled(enabled); }
        void stage(long id, SystemTransitPresenter.Stage stage) {
            presenter.onStage(id, 0, TARGET, stage, 0xff7e57c2, "light");
        }
    }

    private static final class FakeHost implements SystemTransitPresenter.WindowHost {
        TransitSessionPolicy.Session session;
        boolean visible = true;
        boolean failShow;
        boolean failDismiss;
        int visibilityChecks;
        int shows;
        @Override public boolean canShow() { visibilityChecks++; return visible; }
        @Override public void show(TransitSessionPolicy.Session value) {
            shows++;
            if (failShow) throw new IllegalStateException("window unavailable");
            session = value;
        }
        @Override public void dismiss() {
            session = null;
            if (failDismiss) throw new IllegalStateException("display removed");
        }
    }

    private static final class FakeDispatcher implements SystemTransitPresenter.Dispatcher {
        long now;
        boolean currentThread;
        final List<Task> pending = new ArrayList<>();
        @Override public void post(Runnable task) { pending.add(new Task(now, task)); }
        @Override public void postDelayed(Runnable task, long delay) { pending.add(new Task(now + delay, task)); }
        @Override public boolean isCurrentThread() { return currentThread; }
        void runReady() {
            for (int index = 0; index < pending.size();) {
                Task task = pending.get(index);
                if (task.when <= now) {
                    pending.remove(index);
                    task.runnable.run();
                    index = 0;
                } else index++;
            }
        }
    }

    private static final class Task {
        final long when;
        final Runnable runnable;
        Task(long when, Runnable runnable) { this.when = when; this.runnable = runnable; }
    }
}
