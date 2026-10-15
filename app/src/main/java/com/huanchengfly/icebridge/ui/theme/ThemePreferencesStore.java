package com.huanchengfly.icebridge.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArraySet;

/** App-process theme settings. Activities and the system transit snapshot share the same seed. */
public final class ThemePreferencesStore {
    public static final String PREFS = "appearance";
    private static ThemePreferencesStore instance;

    private final SharedPreferences preferences;
    private final Random random = new Random();
    private final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private ThemePolicy.State state;

    public static synchronized ThemePreferencesStore get(Context context) {
        if (instance == null) {
            instance = new ThemePreferencesStore(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        }
        return instance;
    }

    private ThemePreferencesStore(SharedPreferences preferences) {
        this.preferences = preferences;
        state = ThemePolicy.restore(
                preferences.getInt("fixed_seed_color", ThemePolicy.DEFAULT_SEED_COLOR),
                preferences.getBoolean("random_enabled", false),
                preferences.getString("random_interval", ThemePolicy.INTERVAL_DAILY),
                preferences.getString("appearance", ThemePolicy.APPEARANCE_SYSTEM),
                preferences.getInt("random_seed_color", 0),
                preferences.getString("random_date", ""));
    }

    /**
     * Reading alone never begins a launch session or rotates the color. The Provider uses this
     * exact snapshot; foreground UI may use resolve() for an up-to-date daily color.
     */
    public synchronized ThemePolicy.State getSnapshot() { return state; }

    public ThemePolicy.State resolve() { return resolve(LocalDate.now()); }

    public synchronized ThemePolicy.State resolve(LocalDate today) {
        return update(ThemePolicy.resolve(state, today, random::nextInt));
    }

    public ThemePolicy.State beginUserSession() { return beginUserSession(LocalDate.now()); }

    public synchronized ThemePolicy.State beginUserSession(LocalDate today) {
        return update(ThemePolicy.beginUserSession(state, today, random::nextInt));
    }

    public synchronized ThemePolicy.State setFixedSeedColor(int color) {
        return update(ThemePolicy.setFixedSeedColor(state, color));
    }

    public synchronized ThemePolicy.State setRandomEnabled(boolean enabled) {
        return update(ThemePolicy.setRandomEnabled(state, enabled, LocalDate.now(), random::nextInt));
    }

    public synchronized ThemePolicy.State setRandomInterval(String interval) {
        return update(ThemePolicy.setRandomInterval(state, interval, LocalDate.now(), random::nextInt));
    }

    public synchronized ThemePolicy.State setAppearance(String appearance) {
        return update(ThemePolicy.setAppearance(state, appearance));
    }

    /**
     * Listeners run after a complete logical update, on the same thread as the mutation. Additive
     * subscriptions let Provider synchronization coexist with foreground theme rendering.
     */
    public void addListener(Runnable listener) { listeners.add(listener); }

    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private ThemePolicy.State update(ThemePolicy.State updated) {
        if (updated.equals(state)) return state;
        state = updated;
        preferences.edit()
                .putInt("fixed_seed_color", updated.getFixedSeedColor())
                .putBoolean("random_enabled", updated.isRandomEnabled())
                .putString("random_interval", updated.getRandomInterval())
                .putString("appearance", updated.getAppearance())
                .putInt("random_seed_color", updated.getRandomSeedColor())
                .putString("random_date", updated.getRandomDate())
                .apply();
        for (Runnable listener : listeners) listener.run();
        return state;
    }
}
