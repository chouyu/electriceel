package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioTracksSinceBootTest {
    private static final long BOOT_WALL_MS = 1_792_000_000_000L;
    /**
     * Elapsed realtime, and the uptime: it stands still while the device sleeps
     */
    private final long[] mNow = {0};
    private final long[] mUptime = {0};

    @Before
    public void setUp() {
        AudioTracksSinceBoot.resetForTesting();
        getContext().getSharedPreferences(AudioTracksSinceBoot.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        useClocks();
    }

    @After
    public void tearDown() {
        AudioTracksSinceBoot.resetForTesting();
    }

    /**
     * Not counting yet: nothing to tell, and the state is sent without it
     */
    @Test
    public void beforeItCountsThereIsNothing() throws Exception {
        assertNull(AudioTracksSinceBoot.build());
    }

    /**
     * A new process of the same boot goes on from the counts its process before saved: the device's churn is every process's
     */
    @Test
    public void theProcessesOfABootAddUp() throws Exception {
        setBootCount(5);
        mNow[0] = 10_000;
        AudioTracksSinceBoot.init(getContext());
        built(3);
        AudioTracksSinceBoot.onRefused();
        AudioTracksSinceBoot.onRefused();
        mNow[0] = 20_000;
        AudioTracksSinceBoot.onFailed();

        // Killed, and a new process started
        newProcess();
        mNow[0] = 50_000;
        AudioTracksSinceBoot.init(getContext());
        built(2);
        mNow[0] = 60_000;
        AudioTracksSinceBoot.onFailed();

        JSONObject counts = AudioTracksSinceBoot.build();

        assertEquals(50_000, counts.getLong("countedMs"));
        assertEquals(5, counts.getLong("built"));
        assertEquals(2, counts.getLong("refused"));
        assertEquals(2, counts.getLong("failed"));
        // The first failure of the boot, and the tracks before it
        assertEquals(40_000, counts.getLong("firstFailureMsAgo"));
        assertEquals(3, counts.getLong("builtBeforeFirstFailure"));
        assertEquals(10_000, counts.getLong("lastBuiltMsAgo"));
    }

    /**
     * Another boot count: the device restarted, and the counts start over
     */
    @Test
    public void anotherBootStartsOver() throws Exception {
        setBootCount(5);
        mNow[0] = 100_000;
        AudioTracksSinceBoot.init(getContext());
        built(4);
        AudioTracksSinceBoot.onFailed();

        newProcess();
        setBootCount(6);
        mNow[0] = 200_000;
        AudioTracksSinceBoot.init(getContext());

        assertStartedOver(AudioTracksSinceBoot.build());
    }

    /**
     * Saved later in a boot than now, e.g. restored from a backup of another device with the same boot count: not this boot's
     */
    @Test
    public void countsSavedPastNowAreAnotherBoots() throws Exception {
        setBootCount(5);
        mNow[0] = 900_000;
        AudioTracksSinceBoot.init(getContext());
        built(4);

        newProcess();
        mNow[0] = 30_000;
        AudioTracksSinceBoot.init(getContext());

        assertStartedOver(AudioTracksSinceBoot.build());
    }

    /**
     * Without a boot count, the time the device booted tells the boots apart, the clock's small corrections allowed
     */
    @Test
    public void withoutABootCountTheBootTimeTells() throws Exception {
        mNow[0] = 10_000;
        AudioTracksSinceBoot.init(getContext());
        built(2);

        // The same boot, its clock corrected by 20 s
        newProcess();
        mNow[0] = 40_000;
        AudioTracksSinceBoot.sWallClock = () -> BOOT_WALL_MS + 20_000 + mNow[0];
        AudioTracksSinceBoot.init(getContext());
        assertEquals(2, AudioTracksSinceBoot.build().getLong("built"));

        // Booted a day later
        newProcess();
        mNow[0] = 50_000;
        AudioTracksSinceBoot.sWallClock = () -> BOOT_WALL_MS + 86_400_000 + mNow[0];
        AudioTracksSinceBoot.init(getContext());
        assertStartedOver(AudioTracksSinceBoot.build());
    }

    /**
     * When one last opened, and how long the device slept since: e.g. the refusals began after it woke
     */
    @Test
    public void theSleepSinceOneLastOpenedIsTold() throws Exception {
        setBootCount(5);
        mNow[0] = 1_000;
        mUptime[0] = 1_000;
        AudioTracksSinceBoot.init(getContext());
        built(1);

        // A minute later, half of it asleep
        mNow[0] = 61_000;
        mUptime[0] = 31_000;
        JSONObject counts = AudioTracksSinceBoot.build();

        assertEquals(60_000, counts.getLong("lastBuiltMsAgo"));
        assertEquals(30_000, counts.getLong("sleptMsSinceLastBuilt"));
        assertTrue(counts.isNull("firstFailureMsAgo"));
        assertTrue(counts.isNull("builtBeforeFirstFailure"));
    }

    private static void assertStartedOver(JSONObject counts) throws Exception {
        assertEquals(0, counts.getLong("countedMs"));
        assertEquals(0, counts.getLong("built"));
        assertEquals(0, counts.getLong("failed"));
        assertTrue(counts.isNull("firstFailureMsAgo"));
        assertTrue(counts.isNull("lastBuiltMsAgo"));
        assertTrue(counts.isNull("sleptMsSinceLastBuilt"));
    }

    private static void built(int count) {
        for (int i = 0; i < count; i++) {
            AudioTracksSinceBoot.onBuilt();
        }
    }

    /**
     * Nothing in memory: what's saved is all a new process has
     */
    private void newProcess() {
        AudioTracksSinceBoot.resetForTesting();
        useClocks();
    }

    private void useClocks() {
        AudioTracksSinceBoot.sClock = () -> mNow[0];
        AudioTracksSinceBoot.sUptime = () -> mUptime[0];
        AudioTracksSinceBoot.sWallClock = () -> BOOT_WALL_MS + mNow[0];
    }

    private static void setBootCount(int bootCount) {
        Settings.Global.putInt(getContext().getContentResolver(), Settings.Global.BOOT_COUNT, bootCount);
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
