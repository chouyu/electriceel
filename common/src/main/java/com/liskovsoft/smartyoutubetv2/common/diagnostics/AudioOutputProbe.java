package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a refused AudioTrack leaves to tell (see ErrorFixerController): the platform's reason for it in the app's own log, whether the
 * device takes a new track of 44.1 kHz and of 48 kHz now, of the player's config, low-latency or float, whether its output is broken (it
 * gives no buffer size for them), how many players every app has, and how long ago an output came or went (e.g. the TV or the receiver
 * switched off and on). A Shield refused every 44.1 kHz track, a new process's first one too, till the device was restarted (FARAJV5R
 * #40–#41, 9M6NBWN7): the app held none, and nothing told why, whether a 48 kHz one (Opus) or one on another output would have
 * played, whether another app filled the mixer, or what it started with.
 */
public final class AudioOutputProbe {
    /**
     * AAC's and Opus's, as YouTube serves them
     */
    private static final int[] SAMPLE_RATES = {44_100, 48_000};
    /**
     * The player's refusal is in the log this long before it's told: DefaultAudioSink retries for 200 ms (see AudioTrackInitRetry)
     */
    private static final long LOG_WINDOW_MS = 3_000;
    /**
     * logcat -d exits once it has written what it has. Killed past this: the main thread waits on it.
     */
    private static final long LOG_TIMEOUT_MS = 2_000;
    private static final int MAX_LOG_READ_LINES = 2_000;
    private static final int MAX_LOG_LINES = 12;
    private static final int MAX_LOG_LINE_CHARS = 300;
    /**
     * logcat -v brief: "E/AudioTrack( 1234): createTrack_l(0): AudioFlinger could not create track, status: -12 output 13"
     */
    private static final Pattern BRIEF_LINE = Pattern.compile("^([VDIWEFA])/(.+?)\\(\\s*\\d+\\): (.*)$");
    /**
     * The native status of a refused track (status_t, e.g. -12 NO_MEMORY, -19 NO_INIT), most telling first: the JNI's line (what the
     * track's setup returned), AudioTrack's own (AudioFlinger's answer: "status: 0 output 0" when it gave no output, which the setup
     * returns as NO_INIT), any line of an audio tag. A failure only: negative. Not the Java one's "Error code -20" (it's the JNI's setup
     * failing, always).
     */
    private static final Pattern[] STATUS_PATTERNS = {
            Pattern.compile("Error (-\\d+) initializing AudioTrack"),
            Pattern.compile("could not create track, status:? (-\\d+)"),
            Pattern.compile("status:? (-\\d+)")
    };
    /**
     * DefaultAudioSink's, under the platform's tag: what the player did, not the platform's reason
     */
    private static final String OWN_RETRY_LINE = "Retrying AudioTrack creation";
    /**
     * The outputs there are, by ID (see AudioDeviceInfo#getId): one added or removed is a change
     */
    private static final Set<Integer> sOutputIds = new HashSet<>();
    /**
     * When the outputs began to be watched, and when they last changed (elapsed realtime), -1 for not yet
     */
    private static long sWatchedSinceMs = -1;
    private static long sChangedMs = -1;
    @VisibleForTesting
    static LogSource sLogSource = AudioOutputProbe::readOwnLog;
    /**
     * When the last probe ended (wall clock), 0 for none: its own tracks' lines are before it
     */
    private static volatile long sLastProbeEndMs;

    /**
     * The app's own log lines since a time (wall clock), as logcat -v brief writes them
     */
    @VisibleForTesting
    interface LogSource {
        List<String> read(long sinceWallMs) throws IOException;
    }

    private AudioOutputProbe() {
    }

    /**
     * The outputs are watched from now on, for when one last came or went. Once a process.
     */
    public static synchronized void init(Context context) {
        if (sWatchedSinceMs >= 0 || context == null) {
            return;
        }

        AudioManager audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        if (audioManager == null) {
            return;
        }

        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                sOutputIds.add(device.getId());
            }

            // Told the outputs there are at once: no change, they're known
            audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    onOutputs(addedDevices, true);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    onOutputs(removedDevices, false);
                }
            }, new Handler(Looper.getMainLooper()));
            sWatchedSinceMs = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {
            // Diagnostics only: unwatched, the changes are unknown
            android.util.Log.e("AudioOutputProbe", "Can't watch the audio outputs: " + e);
        }
    }

    private static synchronized void onOutputs(@Nullable AudioDeviceInfo[] devices, boolean isAdded) {
        if (devices == null) {
            return;
        }

        boolean isChanged = false;

        for (AudioDeviceInfo device : devices) {
            if (!device.isSink()) {
                continue;
            }

            isChanged |= isAdded ? sOutputIds.add(device.getId()) : sOutputIds.remove(device.getId());
        }

        if (isChanged) {
            sChangedMs = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Now, of the player's refused track of the rate, channel mask and buffer size given (0 or less for unknown): e.g. {"nativeStatus":
     * -12, "log": ["E/AudioTrack: createTrack_l(0): AudioFlinger could not create track, status: -12 output 13 (x10)", …],
     * "minBuffer44100": 7056, "track44100": "refused", "minBuffer48000": 7680, "track48000": "ok", "trackAsPlayer": "refused",
     * "trackLowLatency": "ok", "trackFloat": "refused", "players": 3, "outputChangeMsAgo": 5200, "outputsWatchedMs": 123000}.
     * <ul>
     *     <li>nativeStatus and log: what the platform logged in the app's process of the player's refusal, before the probe's own
     *     tracks; nativeStatus null when no line had one, logError when the log couldn't be read</li>
     *     <li>a track is "ok", "refused", or the class of what its making threw</li>
     *     <li>trackAsPlayer: the player's exact config, 16-bit; trackLowLatency (Android 8+): its rate and channels on the device's
     *     low-latency output, 16-bit; trackFloat: its rate and channels as float. 44.1 kHz stereo when the player's isn't known, and no
     *     trackAsPlayer.</li>
     *     <li>the players are every app's (Android 8+)</li>
     *     <li>the change is null with none since the outputs were watched</li>
     * </ul>
     * A part that can't be read is {"error": "…"}, the rest is still there.
     */
    public static JSONObject probe(@Nullable Context context, int sampleRate, int channelMask, int bufferBytes) {
        JSONObject result = new JSONObject();

        try {
            // First: the probe's own tracks may be refused too, and log the same. Nor the probe's before: refusals 2.9 s apart
            // (9M6NBWN7 #1–#2) have the first one's probe in the second one's window.
            long sinceMs = System.currentTimeMillis() - LOG_WINDOW_MS;
            long lastProbeEndMs = sLastProbeEndMs;
            // Past now: the clock was set back since, and the time says nothing
            putLog(result, lastProbeEndMs > sinceMs && lastProbeEndMs <= sinceMs + LOG_WINDOW_MS ? lastProbeEndMs : sinceMs);

            for (int rate : SAMPLE_RATES) {
                int minBufferBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                // Negative: the device's output can't say (an error)
                result.put("minBuffer" + rate, minBufferBytes);
                // 250 ms of 2 channels of 2 bytes, as the player makes it, or the device's least if that's more (see DefaultAudioSink)
                result.put("track" + rate, tryTrack(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBufferBytes, rate), false));
            }

            boolean isPlayerKnown = sampleRate > 0 && channelMask > 0 && bufferBytes > 0;
            int rate = isPlayerKnown ? sampleRate : 44_100;
            int channels = isPlayerKnown ? channelMask : AudioFormat.CHANNEL_OUT_STEREO;

            if (isPlayerKnown) {
                result.put("trackAsPlayer", tryTrack(rate, channels, AudioFormat.ENCODING_PCM_16BIT, bufferBytes, false));
            }

            if (VERSION.SDK_INT >= 26) {
                // Another output than the player's: its own mixer
                result.put("trackLowLatency", tryTrack(rate, channels, AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(rate, channels, AudioFormat.ENCODING_PCM_16BIT), true));
            }

            result.put("trackFloat", tryTrack(rate, channels, AudioFormat.ENCODING_PCM_FLOAT,
                    AudioTrack.getMinBufferSize(rate, channels, AudioFormat.ENCODING_PCM_FLOAT), false));

            AudioManager audioManager = context != null ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;

            if (VERSION.SDK_INT >= 26 && audioManager != null) {
                result.put("players", audioManager.getActivePlaybackConfigurations().size());
            }

            synchronized (AudioOutputProbe.class) {
                long nowMs = SystemClock.elapsedRealtime();
                result.put("outputChangeMsAgo", sChangedMs >= 0 ? nowMs - sChangedMs : JSONObject.NULL);
                result.put("outputsWatchedMs", sWatchedSinceMs >= 0 ? nowMs - sWatchedSinceMs : JSONObject.NULL);
            }
        } catch (JSONException | RuntimeException e) {
            try {
                result.put("error", e.getClass().getSimpleName() + ": " + PlayerFailure.scrub(e.getMessage()));
            } catch (JSONException ignored) {
                // A name and a string
            }
        }

        sLastProbeEndMs = System.currentTimeMillis();

        return result;
    }

    /**
     * The platform's lines about audio in the app's own log since then, and the native status of a refused track in them
     */
    private static void putLog(JSONObject result, long sinceWallMs) throws JSONException {
        List<String> lines;

        try {
            lines = sLogSource.read(sinceWallMs);
        } catch (IOException | RuntimeException e) {
            result.put("logError", e.getClass().getSimpleName() + ": " + PlayerFailure.scrub(e.getMessage()));
            return;
        }

        // Each line once, in the order first seen, with how many times
        Map<String, Integer> counts = new LinkedHashMap<>();
        // By pattern (see STATUS_PATTERNS), the last line's: the player's refusal is at the window's end
        Integer[] statuses = new Integer[STATUS_PATTERNS.length];
        String unreadable = null;

        for (String line : lines) {
            Matcher matcher = BRIEF_LINE.matcher(line);

            if (!matcher.matches()) {
                // e.g. "--------- beginning of main", or logcat's own error
                if (unreadable == null && !line.startsWith("---------") && !line.trim().isEmpty()) {
                    unreadable = line;
                }

                continue;
            }

            String tag = matcher.group(2).trim();
            String message = matcher.group(3);

            if (!tag.toLowerCase(Locale.US).contains("audio") || message.startsWith(OWN_RETRY_LINE)) {
                continue;
            }

            for (int i = 0; i < STATUS_PATTERNS.length; i++) {
                Integer found = findStatus(STATUS_PATTERNS[i], message);

                if (found != null) {
                    statuses[i] = found;
                }
            }

            String entry = matcher.group(1) + "/" + tag + ": " + message;
            Integer count = counts.get(entry);
            counts.put(entry, count != null ? count + 1 : 1);
        }

        JSONArray log = new JSONArray();
        // The last ones: the player's refusal is at the window's end, other audio warnings may come before it
        int skipped = Math.max(0, counts.size() - MAX_LOG_LINES);

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (skipped > 0) {
                skipped--;
                continue;
            }

            String line = entry.getKey().length() > MAX_LOG_LINE_CHARS ? entry.getKey().substring(0, MAX_LOG_LINE_CHARS) + "…" : entry.getKey();
            log.put(PlayerFailure.scrub(entry.getValue() > 1 ? line + " (x" + entry.getValue() + ")" : line));
        }

        Integer status = null;

        // The most telling pattern's, whatever lines came before
        for (Integer found : statuses) {
            if (found != null) {
                status = found;
                break;
            }
        }

        result.put("nativeStatus", status != null ? status : JSONObject.NULL);
        result.put("log", log);

        if (counts.isEmpty() && unreadable != null) {
            result.put("logError", PlayerFailure.scrub(unreadable.length() > MAX_LOG_LINE_CHARS
                    ? unreadable.substring(0, MAX_LOG_LINE_CHARS) + "…" : unreadable));
        }
    }

    @Nullable
    private static Integer findStatus(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                // Too long: not a status
            }
        }

        return null;
    }

    /**
     * The app's warnings and errors since then: an app reads its own log lines without a permission. -t with a time takes the lines
     * since it (Android 7+'s logcat reads seconds since the epoch) and exits once written.
     */
    private static List<String> readOwnLog(long sinceWallMs) throws IOException {
        String since = String.format(Locale.US, "%d.%03d", sinceWallMs / 1000, sinceWallMs % 1000);
        java.lang.Process process = new ProcessBuilder("logcat", "-d", "-v", "brief", "-t", since, "*:W")
                .redirectErrorStream(true)
                .start();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(LOG_TIMEOUT_MS);
                process.destroy();
            } catch (InterruptedException e) {
                // Exited in time
            }
        }, "AudioOutputProbe-log");
        watchdog.setDaemon(true);
        watchdog.start();
        // The last ones: the player's refusal is at the end (logcat -d writes no more than its buffer holds)
        ArrayDeque<String> lines = new ArrayDeque<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (lines.size() == MAX_LOG_READ_LINES) {
                    lines.removeFirst();
                }

                lines.addLast(line);
            }
        } finally {
            watchdog.interrupt();
            process.destroy();
        }

        return new ArrayList<>(lines);
    }

    /**
     * A track of the config made and released at once. Never played. "noBufferSize" when the device's output gave none for it (see
     * AudioTrack#getMinBufferSize).
     */
    private static String tryTrack(int sampleRate, int channelMask, int encoding, int bufferBytes, boolean isLowLatency) {
        if (bufferBytes <= 0) {
            return "noBufferSize";
        }

        AudioTrack track = null;

        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build();

            if (isLowLatency && VERSION.SDK_INT >= 26) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                track = new AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            }

            return track.getState() == AudioTrack.STATE_INITIALIZED ? "ok" : "refused";
        } catch (UnsupportedOperationException e) {
            // AudioTrack.Builder's refusal: "Cannot create AudioTrack"
            return "refused";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        } finally {
            if (track != null) {
                track.release();
            }
        }
    }

    @VisibleForTesting
    static void resetForTesting() {
        sLogSource = AudioOutputProbe::readOwnLog;
        sLastProbeEndMs = 0;
    }
}
