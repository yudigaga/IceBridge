package com.huanchengfly.icebridge.android16;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SystemTransitPresenterTest {
    private static final String TARGET = "com.example.target";

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
        final SystemTransitPresenter presenter = new SystemTransitPresenter(ui, host,
                message -> { throw new IllegalStateException("test logger unavailable"); }, () -> ui.now);
        void stage(long id, SystemTransitPresenter.Stage stage) {
            presenter.onStage(id, 0, TARGET, stage, 0xff7e57c2, "light");
        }
    }

    private static final class FakeHost implements SystemTransitPresenter.WindowHost {
        TransitSessionPolicy.Session session;
        boolean visible = true;
        boolean failShow;
        boolean failDismiss;
        @Override public boolean canShow() { return visible; }
        @Override public void show(TransitSessionPolicy.Session value) {
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
        final List<Task> pending = new ArrayList<>();
        @Override public void post(Runnable task) { pending.add(new Task(now, task)); }
        @Override public void postDelayed(Runnable task, long delay) { pending.add(new Task(now + delay, task)); }
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
