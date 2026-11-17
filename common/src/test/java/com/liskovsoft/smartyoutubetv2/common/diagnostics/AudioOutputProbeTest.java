package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioOutputProbeTest {
    /**
     * Android 11's, of one refused attempt (see AudioTrack.cpp, android_media_AudioTrack.cpp, AudioTrack.java)
     */
    private static final String NATIVE_LINE = "E/AudioTrack( 4242): createTrack_l(0): AudioFlinger could not create track, status: -12 output 13";
    private static final String JNI_LINE = "E/AudioTrack-JNI( 4242): Error -12 initializing AudioTrack";
    private static final String JAVA_LINE = "E/android.media.AudioTrack( 4242): Error code -20 when initializing AudioTrack.";
    private static final String RETRY_LINE = "W/AudioTrack( 4242): Retrying AudioTrack creation: AudioTrack init failed: 0, Config(44100, 12, 56720)";

    @After
    public void tearDown() {
        AudioOutputProbe.resetForTesting();
    }

    /**
     * The platform's reason for the player's refusal: its native status, and its lines once each with how many times. The player's own
     * retry line and the other tags' lines aren't the platform's reason.
     */
    @Test
    public void theRefusalsReasonIsTakenFromTheAppsLog() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("--------- beginning of main");

        for (int i = 0; i < 3; i++) {
            lines.addAll(Arrays.asList(NATIVE_LINE, JNI_LINE, JAVA_LINE, RETRY_LINE, "W/OkHttp( 4242): A connection leaked"));
        }

        JSONObject probe = probe(lines);

        assertEquals(-12, probe.getInt("nativeStatus"));
        assertEquals(Arrays.asList(
                "E/AudioTrack: createTrack_l(0): AudioFlinger could not create track, status: -12 output 13 (x3)",
                "E/AudioTrack-JNI: Error -12 initializing AudioTrack (x3)",
                "E/android.media.AudioTrack: Error code -20 when initializing AudioTrack. (x3)"), toList(probe.getJSONArray("log")));
        assertFalse(probe.has("logError"));
    }

    /**
     * The Java line's -20 is the JNI's setup failing, whatever the reason: not the status
     */
    @Test
    public void theJavaLinesCodeIsNotTheStatus() throws Exception {
        JSONObject probe = probe(Collections.singletonList(JAVA_LINE));

        assertTrue(probe.isNull("nativeStatus"));
        assertEquals(1, probe.getJSONArray("log").length());
    }

    /**
     * Only the JNI's line (e.g. an older Android): its status
     */
    @Test
    public void theJnisStatusIsTakenWithoutTheNativeLine() throws Exception {
        assertEquals(-19, probe(Collections.singletonList("E/AudioTrack-JNI( 77): Error -19 initializing AudioTrack"))
                .getInt("nativeStatus"));
    }

    /**
     * No more than 12 lines, the last ones (the refusal is at the window's end), and what tells where the user is taken out
     */
    @Test
    public void theLinesAreFewAndScrubbed() throws Exception {
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            lines.add("W/AudioSystem( 4242): line " + i + " from 192.168.1.5");
        }

        JSONArray log = probe(lines).getJSONArray("log");

        assertEquals(12, log.length());
        assertEquals("W/AudioSystem: line 8 from <ip>", log.getString(0));
    }

    /**
     * logcat refused the request (e.g. a time format it doesn't read): what it said, for the next version to fix
     */
    @Test
    public void logcatsOwnErrorIsTold() throws Exception {
        JSONObject probe = probe(Collections.singletonList("logcat: -t '1792000000.123' not in time format"));

        assertEquals("logcat: -t '1792000000.123' not in time format", probe.getString("logError"));
        assertEquals(0, probe.getJSONArray("log").length());
        assertTrue(probe.isNull("nativeStatus"));
    }

    /**
     * Not readable at all: why, and the tracks are still tried
     */
    @Test
    public void anUnreadableLogLeavesTheRest() throws Exception {
        AudioOutputProbe.sLogSource = sinceWallMs -> {
            throw new IOException("Cannot run program \"logcat\"");
        };

        JSONObject probe = AudioOutputProbe.probe(getContext(), 44_100, 12, 56_720);

        assertEquals("IOException: Cannot run program \"logcat\"", probe.getString("logError"));
        assertFalse(probe.has("log"));
        assertTrue(probe.has("track44100"));
        assertTrue(probe.has("trackAsPlayer"));
        assertTrue(probe.has("trackFloat"));
    }

    /**
     * The lines of the player's retries: the 3 s before
     */
    @Test
    public void theLogIsReadFromJustBefore() throws Exception {
        long[] since = {0};
        AudioOutputProbe.sLogSource = sinceWallMs -> {
            since[0] = sinceWallMs;
            return Collections.emptyList();
        };
        long beforeMs = System.currentTimeMillis();

        AudioOutputProbe.probe(getContext(), 44_100, 12, 56_720);

        assertTrue(since[0] >= beforeMs - 3_000 && since[0] <= System.currentTimeMillis() - 3_000);
    }

    /**
     * The player's config unknown: no track of it, the others of 44.1 kHz stereo
     */
    @Test
    public void withoutThePlayersConfigItsTrackIsLeftOut() throws Exception {
        JSONObject probe = probe(Collections.emptyList(), 0, 0, 0);

        assertFalse(probe.has("trackAsPlayer"));
        assertTrue(probe.has("trackFloat"));
    }

    private static JSONObject probe(List<String> lines) {
        return probe(lines, 44_100, 12, 56_720);
    }

    private static JSONObject probe(List<String> lines, int sampleRate, int channelMask, int bufferBytes) {
        AudioOutputProbe.sLogSource = sinceWallMs -> lines;

        return AudioOutputProbe.probe(getContext(), sampleRate, channelMask, bufferBytes);
    }

    private static List<String> toList(JSONArray array) throws Exception {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            result.add(array.getString(i));
        }

        return result;
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
