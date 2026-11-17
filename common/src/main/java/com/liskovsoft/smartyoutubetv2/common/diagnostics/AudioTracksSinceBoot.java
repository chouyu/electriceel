package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.audio.AudioTrackCounts;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The AudioTracks the app's players built, and the ones the device refused, since the device booted, every process of the app's
 * counted. A Shield refused every track about 1.5 days after each restart till the next one (FARAJV5R #16, #101; RHV02ZE3): after
 * about as many tracks each time, the app's own churn fills the device (each seek builds a new one); after about as long, it's the
 * time. And when the last one opened, with how long the device slept since: the failures are seen, not when they began.
 */
public final class AudioTracksSinceBoot {
    @VisibleForTesting
    static final String PREFS = "diagnostics_audio_tracks_since_boot";
    private static final String KEY_BOOT_COUNT = "bootCount";
    private static final String KEY_BOOT_WALL_MS = "bootWallMs";
    private static final String KEY_SINCE_MS = "sinceMs";
    private static final String KEY_SAVED_MS = "savedMs";
    private static final String KEY_BUILT = "built";
    private static final String KEY_REFUSED = "refused";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_LAST_BUILT_MS = "lastBuiltMs";
    private static final String KEY_LAST_BUILT_SLEPT_MS = "lastBuiltSleptMs";
    private static final String KEY_FIRST_FAILED_MS = "firstFailedMs";
    private static final String KEY_BUILT_BEFORE_FIRST_FAILED = "builtBeforeFirstFailed";
    /**
     * Without a boot count (the setting unreadable): the same boot if it booted this close to the time saved, the clock's corrections
     * allowed
     */
    private static final long BOOT_WALL_TOLERANCE_MS = 60_000;
    @Nullable
    private static SharedPreferences sPrefs;
    private static int sBootCount = -1;
    private static long sBootWallMs;
    /**
     * Since the device booted (elapsed realtime) the counting began: the app's first process of the boot that counted
     */
    private static long sSinceMs;
    private static long sBuilt;
    private static long sRefused;
    private static long sFailed;
    /**
     * When the last one opened (elapsed realtime), and how long the device had slept since it booted by then, -1 for none
     */
    private static long sLastBuiltMs = -1;
    private static long sLastBuiltSleptMs = -1;
    /**
     * When the first was refused past the retries this boot (elapsed realtime), and the ones built before it, -1 for none
     */
    private static long sFirstFailedMs = -1;
    private static long sBuiltBeforeFirstFailed = -1;
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;
    /**
     * Doesn't run while the device sleeps: elapsed realtime minus this is the sleep since the device booted
     */
    @VisibleForTesting
    static VideoStarts.Clock sUptime = SystemClock::uptimeMillis;
    @VisibleForTesting
    static VideoStarts.Clock sWallClock = System::currentTimeMillis;

    private static final AudioTrackCounts.Listener LISTENER = new AudioTrackCounts.Listener() {
        @Override
        public void onBuilt() {
            AudioTracksSinceBoot.onBuilt();
        }

        @Override
        public void onRefused() {
            AudioTracksSinceBoot.onRefused();
        }

        @Override
        public void onFailed() {
            AudioTracksSinceBoot.onFailed();
        }
    };

    private AudioTracksSinceBoot() {
    }

    /**
     * When the process starts (see MainApplication): the counts so far this boot are read, another boot's dropped, and the players'
     * tracks counted from now on
     */
    public static synchronized void init(Context context) {
        if (sPrefs != null || context == null) {
            return;
        }

        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int bootCount = readBootCount(appContext.getContentResolver());
        long nowMs = sClock.now();
        long bootWallMs = sWallClock.now() - nowMs;

        if (isSameBoot(prefs, bootCount, bootWallMs, nowMs)) {
            sBootCount = prefs.getInt(KEY_BOOT_COUNT, -1);
            sBootWallMs = prefs.getLong(KEY_BOOT_WALL_MS, bootWallMs);
            sSinceMs = prefs.getLong(KEY_SINCE_MS, nowMs);
            sBuilt = prefs.getLong(KEY_BUILT, 0);
            sRefused = prefs.getLong(KEY_REFUSED, 0);
            sFailed = prefs.getLong(KEY_FAILED, 0);
            sLastBuiltMs = prefs.getLong(KEY_LAST_BUILT_MS, -1);
            sLastBuiltSleptMs = prefs.getLong(KEY_LAST_BUILT_SLEPT_MS, -1);
            sFirstFailedMs = prefs.getLong(KEY_FIRST_FAILED_MS, -1);
            sBuiltBeforeFirstFailed = prefs.getLong(KEY_BUILT_BEFORE_FIRST_FAILED, -1);
        } else {
            sBootCount = bootCount;
            sBootWallMs = bootWallMs;
            sSinceMs = nowMs;
            sBuilt = 0;
            sRefused = 0;
            sFailed = 0;
            sLastBuiltMs = -1;
            sLastBuiltSleptMs = -1;
            sFirstFailedMs = -1;
            sBuiltBeforeFirstFailed = -1;
        }

        sPrefs = prefs;
        save();
        AudioTrackCounts.setListener(LISTENER);
    }

    /**
     * Since the counting began this boot: e.g. {"countedMs": 129600000, "built": 5210, "refused": 31, "failed": 3,
     * "firstFailureMsAgo": 4100, "builtBeforeFirstFailure": 5210, "lastBuiltMsAgo": 4300, "sleptMsSinceLastBuilt": 0}. A time is
     * null with none. Null before init.
     */
    @Nullable
    public static synchronized JSONObject build() throws JSONException {
        if (sPrefs == null) {
            return null;
        }

        long nowMs = sClock.now();
        JSONObject result = new JSONObject();
        result.put("countedMs", nowMs - sSinceMs);
        result.put("built", sBuilt);
        result.put("refused", sRefused);
        result.put("failed", sFailed);
        result.put("firstFailureMsAgo", sFirstFailedMs >= 0 ? nowMs - sFirstFailedMs : JSONObject.NULL);
        result.put("builtBeforeFirstFailure", sFirstFailedMs >= 0 ? sBuiltBeforeFirstFailed : JSONObject.NULL);
        result.put("lastBuiltMsAgo", sLastBuiltMs >= 0 ? nowMs - sLastBuiltMs : JSONObject.NULL);
        result.put("sleptMsSinceLastBuilt", sLastBuiltMs >= 0 ? getSleptMs() - sLastBuiltSleptMs : JSONObject.NULL);

        return result;
    }

    @VisibleForTesting
    static synchronized void onBuilt() {
        sBuilt++;
        sLastBuiltMs = sClock.now();
        sLastBuiltSleptMs = getSleptMs();
        save();
    }

    @VisibleForTesting
    static synchronized void onRefused() {
        sRefused++;
        save();
    }

    @VisibleForTesting
    static synchronized void onFailed() {
        sFailed++;

        if (sFirstFailedMs < 0) {
            sFirstFailedMs = sClock.now();
            sBuiltBeforeFirstFailed = sBuilt;
        }

        save();
    }

    /**
     * Since the device booted
     */
    private static long getSleptMs() {
        return Math.max(0, sClock.now() - sUptime.now());
    }

    /**
     * The counts saved are this boot's: its boot count, or else the time it booted, the same, and saved before now (a time past now is
     * a boot's before this one, e.g. restored from a backup)
     */
    private static boolean isSameBoot(SharedPreferences prefs, int bootCount, long bootWallMs, long nowMs) {
        if (!prefs.contains(KEY_SAVED_MS) || prefs.getLong(KEY_SAVED_MS, Long.MAX_VALUE) > nowMs) {
            return false;
        }

        int savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1);

        if (bootCount >= 0 || savedBootCount >= 0) {
            return bootCount == savedBootCount;
        }

        return Math.abs(prefs.getLong(KEY_BOOT_WALL_MS, Long.MIN_VALUE / 2) - bootWallMs) <= BOOT_WALL_TOLERANCE_MS;
    }

    private static int readBootCount(ContentResolver resolver) {
        try {
            return Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Each change: a process killed loses none of them. Async, a small file.
     */
    private static void save() {
        if (sPrefs == null) {
            return;
        }

        sPrefs.edit()
                .putInt(KEY_BOOT_COUNT, sBootCount)
                .putLong(KEY_BOOT_WALL_MS, sBootWallMs)
                .putLong(KEY_SINCE_MS, sSinceMs)
                .putLong(KEY_SAVED_MS, sClock.now())
                .putLong(KEY_BUILT, sBuilt)
                .putLong(KEY_REFUSED, sRefused)
                .putLong(KEY_FAILED, sFailed)
                .putLong(KEY_LAST_BUILT_MS, sLastBuiltMs)
                .putLong(KEY_LAST_BUILT_SLEPT_MS, sLastBuiltSleptMs)
                .putLong(KEY_FIRST_FAILED_MS, sFirstFailedMs)
                .putLong(KEY_BUILT_BEFORE_FIRST_FAILED, sBuiltBeforeFirstFailed)
                .apply();
    }

    /**
     * Not counting, nothing in memory: the next init reads what's saved, as a new process does
     */
    @VisibleForTesting
    static synchronized void resetForTesting() {
        AudioTrackCounts.setListener(null);
        sPrefs = null;
        sClock = SystemClock::elapsedRealtime;
        sUptime = SystemClock::uptimeMillis;
        sWallClock = System::currentTimeMillis;
    }
}
