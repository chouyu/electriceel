package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.SystemClock;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.AudioTrackCounts;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderErrorException;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.PoTokenError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrProtocolError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.UnknownPartError;
import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart.PoTokenStatus;
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.AudioOutputProbe;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.Connections;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.FormatAttempts;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.LoadErrors;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerCommands;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailure;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailureLog;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.ProtectionStatuses;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.VideoStarts;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DecoderErrorFallback;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector.OnLongBuffering;
import com.liskovsoft.smartyoutubetv2.common.misc.MembersVideos;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class ErrorFixerController extends BasePlayerController implements OnLongBuffering {
    private static final String TAG = ErrorFixerController.class.getSimpleName();
    private static final long STREAM_END_THRESHOLD_MS = 180_000;
    /**
     * A video stalled this close to its end has ended: nothing's left to load there (e.g. its whole-second length is past its
     * media), and a reload played it again from 0:00
     */
    private static final long VIDEO_END_THRESHOLD_MS = 2_000;
    private static final int MAX_SABR_ERROR_FIX_ATTEMPTS = 3;
    private static final int MAX_NETWORK_ERROR_RELOADS = 3;
    /**
     * Stalled long again within this: the quality is lowered rather than reloading again
     */
    private static final long REPEATED_STALL_MS = 3 * 60 * 1_000;
    /**
     * Refused again within this after its player response was fetched again: the fresh one didn't help
     */
    private static final long REPEATED_SABR_ERROR_MS = 3 * 60 * 1_000;
    private static final long AUDIO_PLAYED_MS = 1_000;
    private static final int UNPLAYABLE_RELOAD_MS = 30_000;
    private static final long AUDIO_PLAYED_CHECK_MS = 2_000;
    /**
     * Of a reload or an engine restart, as before: the player's error passes first
     */
    private static final long RETRY_MS = 1_000;
    /**
     * The longest wait before a fix is tried again (see RetryBackoff)
     */
    private static final long MAX_RETRY_MS = 30_000;
    /**
     * Refused AudioTracks of a video, with nothing left to fall back to, restarted at once: past them, each restart waits longer
     */
    private static final int MAX_AUDIO_INIT_RESTARTS = 2;
    /**
     * Of mDeviceAudioRetries: the device's, whatever the video
     */
    private static final String DEVICE_AUDIO_RETRY_KEY = "device";
    /**
     * A load failed on its connection this recently: the connection is what fails a start that hangs (see LoadErrors)
     */
    private static final long CONNECTION_ERROR_WINDOW_MS = 30_000;
    private final BufferingDetector mBufferingDetector = new BufferingDetector(this);
    private VideoLoaderController mVideoLoaderController;
    private int mDecoderErrorCount;
    /**
     * The video the decoder error count and fallbacks are for (see applyDecoderErrorFallback)
     */
    private String mDecoderErrorVideoId;
    private FormatItem mVideoFallback;
    private FormatItem mAudioFallback;
    /**
     * Refused AudioTracks on the video in mDecoderErrorVideoId, since its sound last played
     */
    private int mAudioInitErrorCount;
    /**
     * Of the video, the engine restarts for refused AudioTracks past MAX_AUDIO_INIT_RESTARTS: the device refused each track for 20
     * minutes (FARAJV5R #16–#41), restarted 40 times a minute
     */
    private final RetryBackoff mAudioInitRetries = new RetryBackoff(2 * RETRY_MS, MAX_RETRY_MS);
    /**
     * The device refuses every AudioTrack, not the app's (see applyDeviceAudioRefusal), till a video's sound plays: whatever video
     */
    private boolean mIsAudioRefusedByDevice;
    /**
     * The engine restarts while the device refuses every AudioTrack: one wait growing across the videos, not each video's own
     */
    private final RetryBackoff mDeviceAudioRetries = new RetryBackoff(2 * RETRY_MS, MAX_RETRY_MS);
    /**
     * Of the video, the fetches of its formats that failed, till its formats come (see onSourceChanged): 55 fetches a minute for 15
     * minutes while the network was down (FARAJV5R #47–#99)
     */
    private final RetryBackoff mFetchRetries = new RetryBackoff(RETRY_MS, MAX_RETRY_MS);
    /**
     * Of the video, the reloads of a start that hangs on a connection that fails, till it plays (see runLongBufferingAction)
     */
    private final RetryBackoff mStallRetries = new RetryBackoff(RETRY_MS, MAX_RETRY_MS);
    /**
     * The DNS the DNS fix switched from (see runLongBufferingAction), till a video plays, -1 for none: put back when the player's
     * connections fail the same way with Google's (it didn't help). Before, it stayed after the same timeouts went on (S4PGCB6X
     * #7–#19), each lookup 500 ms longer from then on.
     */
    private int mDnsFixFrom = -1;
    /**
     * A DNS fix was undone: not tried again (see mDnsFixFrom)
     */
    private boolean mIsDnsFixRefuted;
    private String mSabrErrorFixVideoId;
    private int mSabrErrorFixAttempts;
    /**
     * The video the reloads on a broken connection are counted for (see applyNetworkErrorReload)
     */
    private String mNetworkErrorVideoId;
    private int mNetworkErrorReloads;
    /**
     * The last SabrError fixed, and when (see applySabrProtocolErrorAction)
     */
    private String mSabrProtocolErrorVideoId;
    private long mSabrProtocolErrorTimeMs;
    /**
     * The video reloaded once for a first segment that never came (see runLongBufferingAction)
     */
    private String mStuckLoadVideoId;
    /**
     * The last long stall (see isRepeatedStall)
     */
    private String mStallVideoId;
    private long mStallTimeMs;
    /**
     * Where the playback started (see onPlay)
     */
    private long mPlayStartPositionMs;
    /**
     * When the player's source last opened (see onSourceChanged), before its streams' first request: a protection status told
     * before is of a stream before (see isAttestationPending)
     */
    private long mSourceChangedMs;
    /**
     * Still playing and its clock moved on: the audio's clock is the player's, so its sound is output (see FormatAttempts)
     */
    private final Runnable mCheckAudioPlayed = () -> {
        if (getPlayer() != null && getPlayer().isPlaying() && getPlayer().getPositionMs() - mPlayStartPositionMs >= AUDIO_PLAYED_MS) {
            FormatAttempts.onAudioPlayed();
            // A track refused after this is another time's
            mAudioInitErrorCount = 0;
            mAudioInitRetries.reset();
            mIsAudioRefusedByDevice = false;
            mDeviceAudioRetries.reset();
        }
    };


    @Override
    public void onInit() {
        mVideoLoaderController = getController(VideoLoaderController.class);
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        Log.e(TAG, "Player error occurred: %s. Trying to fix…", type);

        PlayerFailure failure = newFailure(PlayerFailure.KIND_ENGINE_ERROR).engineError(type, rendererIndex, error);
        mBufferingDetector.reset();
        Utils.removeCallbacks(mCheckAudioPlayed);
        FormatAttempts.onPlaying(false);
        markFailedStream(type, rendererIndex, error);

        try {
            runEngineErrorAction(type, rendererIndex, error, failure);
        } finally {
            failure.record();
        }
    }

    @Override
    public void onLongBuffering() {
        if (getPlayer() == null) {
            return;
        }

        PlayerFailure failure = newFailure(PlayerFailure.KIND_STALL);
        FormatAttempts.addStallDetails(failure, getPlayer().getPositionMs());

        try {
            runLongBufferingAction(failure);
        } finally {
            failure.record();
        }
    }

    private void runLongBufferingAction(PlayerFailure failure) {
        if (isStreamEnded() || isAtVideoEnd()) {
            failure.action("play_end");
            getMainController().onPlayEnd();
        } else if (isOfflineVideo() && isAttestationPending()) {
            // Past its grace period, the server withholds the media while the poToken's attestation is pending: stalled at 0:59,
            // the same client's reload froze there (51AQC7FS). The SABR stream tells it as a PoTokenError (see
            // applySabrPoTokenAction): this is a stall it didn't tell.
            failure.action("switch_next_client_now").action("reload_video");
            YouTubeServiceManager.instance().switchNextClientNow();
            mVideoLoaderController.reloadVideo();
        } else if (isOfflineVideo() && isSubtitlesEnabled()) {
            // Long loading subtitles cause hangs
            failure.action("disable_subtitles").action("reload_video");
            disableSubtitles();
            mVideoLoaderController.reloadVideo();
        } else if (isOfflineVideo() && !mBufferingDetector.isPlayable() && isFirstStuckLoad()) {
            // One track's first segment came and the other's request hangs: the name resolved and the client's formats load, so
            // neither the DNS nor the client is to blame. Reloaded once for the video, then the fixes below.
            failure.action("stuck_load_reload").action("reload_video");
            mVideoLoaderController.reloadVideo();
        } else if (isOfflineVideo() && !mBufferingDetector.isPlayable()) {
            int customDns = PlayerTweaksData.DNS_TYPE_GOOGLE;
            // What the player's new connections say of their lookup: only OkHttp's are told (the DNS setting is OkHttp's too), so
            // another engine's hang has nothing to go by, and the DNS isn't touched for it (S4PGCB6X #7: Cronet, the same timeouts
            // after the switch)
            Connections.Lookup lookup = Connections.getLookup();
            failure.detail("lookup", lookup.name().toLowerCase(Locale.US));

            if (lookup == Connections.Lookup.FAILING && getPlayerTweaksData().getPreferredDnsType() != customDns
                    && !mIsDnsFixRefuted && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                // The formats came, so the network is up, and the player's lookups failed or gave addresses none of which answer:
                // Google's DNS may give working ones. Kept if a video plays, put back if they fail the same way with it.
                // The DNS is read at each lookup (see PreferredDns in SharedModules): the engine's restart applies it, not the app's
                MessageHelpers.showLongMessage(getContext(), "Fixing incorrect DNS resolution...");
                mDnsFixFrom = getPlayerTweaksData().getPreferredDnsType();
                failure.action("dns_google").action("restart_engine");
                getPlayerTweaksData().setPreferredDnsType(customDns);
                mVideoLoaderController.restartEngine();
            } else {
                // The DNS fix of the last start that hung didn't help: the lookups fail the same way with Google's
                if (lookup == Connections.Lookup.FAILING) {
                    undoDnsFix(failure);
                }

                long delayMs = RETRY_MS;

                if (LoadErrors.hasConnectionErrorWithin(CONNECTION_ERROR_WINDOW_MS)) {
                    // A load's connection failed (it didn't connect, or no answer came): every client streams from the same servers,
                    // another one wouldn't help (FARAJV5R #46, S4PGCB6X #10, #11, #15). Tried again later each time.
                    failure.action("network_wait");
                    delayMs = mStallRetries.next(getVideo().videoId);
                } else {
                    // Also, some clients like ANDROID_REEL may just hang at start
                    MessageHelpers.showLongMessage(getContext(), "Fixing stalled client...");
                    failure.action("switch_next_client_now");
                    YouTubeServiceManager.instance().switchNextClientNow();
                }

                failure.action(delayed("reload_video", delayMs));
                mVideoLoaderController.reloadVideo((int) delayMs);
            }
        } else if (isOfflineVideo() && isRepeatedStall()) {
            // A reload didn't help. Its loads failed on their connection (they didn't connect, or no answer came), or they came faster
            // than the format plays: the connection stops answering, it isn't too slow for the format, and a lower one stalls the same
            // way (KKSJMSQX #2: neither track loaded for 20 s at 30.7 Mbps of a 20.3 Mbps format; 1440p was lowered for the video, and
            // the read timed out again at 1080p, #4). Tried again later each time instead.
            boolean hasConnectionError = LoadErrors.hasConnectionErrorWithin(CONNECTION_ERROR_WINDOW_MS);
            failure.detail("loadConnectionError", hasConnectionError);

            if (hasConnectionError || FormatAttempts.isFastEnough()) {
                failure.action("network_wait");
                long delayMs = mStallRetries.next(getVideo() != null ? getVideo().videoId : null);
                failure.action(delayed("reload_video", delayMs));
                mVideoLoaderController.reloadVideo((int) delayMs);
            } else if (lowerVideoQuality()) {
                // The connection can't keep up with this format right now. For this video only.
                failure.action("lower_quality").action("restart_engine");
                mVideoLoaderController.restartEngine();
            } else {
                failure.action("reload_video");
                mVideoLoaderController.reloadVideo();
            }
        } else {
            // SABR may hang if the server issues a high backoffTime
            failure.action("reload_video");
            mVideoLoaderController.reloadVideo();
        }
    }

    @Override
    public void onBuffering() {
        mBufferingDetector.onStartBuffering();
        FormatAttempts.onBuffering();
    }

    @Override
    public void onSeekEnd() {
        mBufferingDetector.reset();
        // Needed to detect additional buffering (e.g. hanged clients).
        // Don't worry this event will be canceled by subsequent onPlay() or onPause() if everything is ok.
        // Only when it plays: paused where it never gets ready (e.g. past the media), neither comes, and the "stall" reloaded the
        // video from 0:00. Played from there, its buffering is checked (see onBuffering())
        if (getPlayer() != null && getPlayer().getPlayWhenReady()) {
            mBufferingDetector.onStartBuffering();
        }

        // The jump isn't the audio playing: measured from here
        if (getPlayer() != null) {
            mPlayStartPositionMs = getPlayer().getPositionMs();
        }

        FormatAttempts.onSeek();
    }

    @Override
    public void onSeekPositionChanged(long positionMs) {
        // Its buffering isn't a stall
        FormatAttempts.onSeek();
    }

    @Override
    public void onPlay() {
        mBufferingDetector.onStopBuffering();
        mSabrErrorFixAttempts = 0;
        mStallRetries.reset();
        // A video played after it: the DNS fix stays
        mDnsFixFrom = -1;
        // It came: stuck again another time (e.g. opened again), it's reloaded again
        mStuckLoadVideoId = null;
        FormatAttempts.onPlaying(true);

        if (getPlayer() != null) {
            mPlayStartPositionMs = getPlayer().getPositionMs();
            Utils.removeCallbacks(mCheckAudioPlayed);
            Utils.postDelayed(mCheckAudioPlayed, AUDIO_PLAYED_CHECK_MS);
        }
    }

    @Override
    public void onPause() {
        mBufferingDetector.onStopBuffering();
        Utils.removeCallbacks(mCheckAudioPlayed);
        FormatAttempts.onPlaying(false);
    }

    /**
     * The formats reached the player: a fetch that fails after this is another time's
     */
    @Override
    public void onSourceChanged(Video item) {
        mFetchRetries.reset();
        mSourceChangedMs = SystemClock.elapsedRealtime();
    }

    @Override
    public void onNewVideo(Video item) {
        mBufferingDetector.start();
        FormatAttempts.onNewVideo(getContext(), item != null ? item.videoId : null);
        PlayerCommands.onNewVideo(item != null ? item.videoId : null);

        if (item != null && item.videoId != null && item.videoId.equals(mDecoderErrorVideoId)) {
            // The same video reloaded (e.g. on a SABR error). The reload cleared the temp formats, the fallback still applies.
            reapplyDecoderErrorFallback();
        } else {
            mDecoderErrorVideoId = item != null ? item.videoId : null;
            mDecoderErrorCount = 0;
            mAudioInitErrorCount = 0;
            mVideoFallback = null;
            mAudioFallback = null;
        }
    }

    @Override
    public void onFinish() {
        mBufferingDetector.reset();
        Utils.removeCallbacks(mCheckAudioPlayed);
        FormatAttempts.end(getContext());
    }

    /**
     * The diagnostics kept in memory are written: in the background the app may be killed
     */
    @Override
    public void onViewPaused() {
        flushDiagnostics();
    }

    private void flushDiagnostics() {
        Context context = getContext();

        if (context == null) {
            return;
        }

        try {
            FormatAttempts.flush(context, false);
            PlayerFailureLog.instance(context).flush();
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't write the diagnostics: %s", e.getMessage());
        }
    }

    /**
     * The formats the player tries, for the failures of the video (see FormatAttempts)
     */
    @Override
    public void onTrackChanged(FormatItem track) {
        if (getPlayer() == null) {
            return;
        }

        try {
            FormatAttempts.onTracks(getPlayer().getVideoFormat(), getPlayer().getAudioFormat());
            FormatAttempts.onOffered(getPlayer().getVideoFormats(), getPlayer().getAudioFormats());
        } catch (RuntimeException e) {
            // Diagnostics only: the playback goes on without them
            Log.e(TAG, "Can't read the formats tried: %s", e.getMessage());
        }
    }

    @Override
    public void onEngineReleased() {
        mBufferingDetector.reset();
        Utils.removeCallbacks(mCheckAudioPlayed);
        FormatAttempts.onPlaying(false);
    }

    /**
     * The error is of the video or the audio of the formats tried (see FormatAttempts). Others (e.g. a 403 of the whole stream)
     * tell nothing of either.
     */
    private static void markFailedStream(int type, int rendererIndex, Throwable error) {
        Boolean isVideo = null;

        String decoderName = null;

        if (error instanceof DecoderErrorException) {
            int trackType = ((DecoderErrorException) error).trackType;
            isVideo = trackType == C.TRACK_TYPE_VIDEO ? Boolean.TRUE : trackType == C.TRACK_TYPE_AUDIO ? Boolean.FALSE : null;
            decoderName = ((DecoderErrorException) error).decoderName;
        } else if (error instanceof DecoderInitializationException) {
            String mimeType = ((DecoderInitializationException) error).mimeType;
            isVideo = MimeTypes.isVideo(mimeType) ? Boolean.TRUE : MimeTypes.isAudio(mimeType) ? Boolean.FALSE : null;
            // Null: no decoder takes it
            String name = ((DecoderInitializationException) error).decoderName;
            decoderName = name != null ? name : "none";
        } else if (error instanceof AudioSink.InitializationException || error instanceof AudioSink.WriteException
                || error instanceof AudioSink.ConfigurationException) {
            isVideo = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER || type == PlayerEventListener.ERROR_TYPE_SOURCE) {
            isVideo = rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO ? Boolean.TRUE :
                    rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO ? Boolean.FALSE : null;
        }

        if (isVideo != null) {
            FormatAttempts.onDecoder(isVideo, decoderName);
            FormatAttempts.onStreamFailed(isVideo);
        }
    }

    private void runEngineErrorAction(int type, int rendererIndex, Throwable error, PlayerFailure failure) {
        // Hide begin errors in embed mode (e.g. wrong date/time: unable to connect to...)
        if (isEmbedPlayer() && getPlayer() != null && getPlayer().getPositionMs() == 0) {
            failure.action("finish_embed");
            getPlayer().finish();
            return;
        }

        if (isStreamEnded() || isAtVideoEnd()) {
            // Url no longer works (e.g. live stream ended), or a video failed in its last seconds (e.g. draining its sound at the end
            // of the stream): it ended. A restart there plays nothing more, and its error message is about a video that played.
            failure.action("play_end");
            getMainController().onPlayEnd();
            return;
        }

        if (applySabrReloadPlayerAction(error, failure)) {
            return;
        }

        if (applySabrBackoffAction(error, failure)) {
            return;
        }

        if (applySabrPoTokenAction(error, failure)) {
            return;
        }

        if (applySabrProtocolErrorAction(error, failure)) {
            return;
        }

        if (mVideoLoaderController.isFixPending()) {
            // The player a reload or a restart replaces failed on its way out, e.g. its load that hung timed out (KKSJMSQX #3: 565 ms
            // after a stall's restart was asked for). The fix asked for covers it: another one put the restart off and showed the
            // error.
            failure.action("fix_pending");
            return;
        }

        applyEngineErrorAction(type, rendererIndex, error, failure);
    }

    /**
     * SABR server sent ReloadPlayerResponse. It won't send any media until the player response is re-fetched with the token.
     */
    private boolean applySabrReloadPlayerAction(Throwable error, PlayerFailure failure) {
        SabrReloadPlayerError reloadError = null;
        Video video = getVideo();

        Throwable cause = error.getCause();
        if (cause instanceof SabrReloadPlayerError) {
            reloadError = (SabrReloadPlayerError) cause;
        }

        if (reloadError == null || video == null) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mSabrErrorFixVideoId)) {
            mSabrErrorFixVideoId = video.videoId;
            mSabrErrorFixAttempts = 0;
        }

        // Reload doesn't help. Fallback to the common error handling (switch the client).
        if (++mSabrErrorFixAttempts > MAX_SABR_ERROR_FIX_ATTEMPTS) {
            return false;
        }

        // The first is the server's routine ask (e.g. its session expired in a long pause): reloaded, it plays on without a word
        if (mSabrErrorFixAttempts > 1) {
            MessageHelpers.showLongMessage(getContext(), "%s. Attempt: %s", reloadError.getMessage(), mSabrErrorFixAttempts);
        }

        failure.action("sabr_reload " + mSabrErrorFixAttempts + "/" + MAX_SABR_ERROR_FIX_ATTEMPTS).action("reload_video");
        YouTubeServiceManager.instance().reloadPlayerResponse(video.videoId, reloadError.reloadPlaybackToken);
        // At once: the error came when the buffer ran out, the picture stands still till the video plays again. A second's wait was
        // more than the reload took (FARAJV5R #100: 674 ms to its first frame).
        mVideoLoaderController.reloadVideo(0);

        return true;
    }

    /**
     * The server told us to back off, honor it instead of re-requesting
     * immediately - otherwise we burn exactly the waiting period the server asked for.
     */
    private boolean applySabrBackoffAction(Throwable error, PlayerFailure failure) {
        SabrBackoffError backoffError = null;
        Video video = getVideo();

        Throwable cause = error.getCause();
        if (cause instanceof SabrBackoffError) {
            backoffError = (SabrBackoffError) cause;
        }

        if (backoffError == null || video == null) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mSabrErrorFixVideoId)) {
            mSabrErrorFixVideoId = video.videoId;
            mSabrErrorFixAttempts = 0;
        }

        // Reload doesn't help. Fallback to the common error handling (switch the client).
        if (++mSabrErrorFixAttempts > MAX_SABR_ERROR_FIX_ATTEMPTS) {
            return false;
        }

        MessageHelpers.showLongMessage(getContext(), "%s. Attempt: %s", backoffError.getMessage(), mSabrErrorFixAttempts);

        failure.action("sabr_backoff " + mSabrErrorFixAttempts + "/" + MAX_SABR_ERROR_FIX_ATTEMPTS).action("reload_video");
        // SABR may hang if the server issues a high backoffTime
        mVideoLoaderController.reloadVideo();

        return true;
    }

    /**
     * SABR server requires attestation, or it's still pending: it won't send media past its grace period (about a minute) with this
     * poToken, at the same position after every reload. A TV token it rejects goes (e.g. the WebView's BotGuard makes bad ones): the
     * client streams without one, like before it had any. Otherwise another client: a pending TV token was a fresh one (51AQC7FS,
     * 0KPV79TF: the app had run under a minute), and without one the server required one (5V8J002W #11).
     */
    private boolean applySabrPoTokenAction(Throwable error, PlayerFailure failure) {
        Throwable cause = error.getCause();

        if (!(cause instanceof PoTokenError) || getVideo() == null) {
            return false;
        }

        PoTokenStatus status = ((PoTokenError) cause).status;

        if (status == PoTokenStatus.INVALID && YouTubeServiceManager.instance().rejectPoToken(mVideoLoaderController.getFormatClient())) {
            failure.action("reject_potoken").action("reload_video");
        } else {
            failure.action("switch_next_client_now").action("reload_video");
            YouTubeServiceManager.instance().switchNextClientNow();
        }

        mVideoLoaderController.reloadVideo();

        return true;
    }

    /**
     * SABR's server refused the request with a SabrError (see SabrProtocolError), e.g. sabr.malformed_config: the player response's
     * ustreamer config no longer applies, and every request after it is refused. Sent again, the request gets the same answer: the
     * player response is fetched again, with the same client. Refused again soon after, the fresh one didn't help: the next client
     * right away. Not its poToken first (see YouTubeServiceManager#switchNextClient): that one may be reset again a minute later, so
     * a client refused each time after that was never left.<br/>
     * Not reset when it plays: a refused player response plays what it loaded before.
     */
    private boolean applySabrProtocolErrorAction(Throwable error, PlayerFailure failure) {
        Throwable cause = error.getCause();
        Video video = getVideo();

        if (!(cause instanceof SabrProtocolError) || video == null) {
            return false;
        }

        long nowMs = SystemClock.elapsedRealtime();

        if (Helpers.equals(video.videoId, mSabrProtocolErrorVideoId) && nowMs - mSabrProtocolErrorTimeMs <= REPEATED_SABR_ERROR_MS) {
            MessageHelpers.showLongMessage(getContext(), cause.getMessage());
            failure.action("switch_next_client_now");
            YouTubeServiceManager.instance().switchNextClientNow();
        } else {
            failure.action("refetch_player");
            YouTubeServiceManager.instance().reloadPlayerResponse(video.videoId, null);
        }

        mSabrProtocolErrorVideoId = video.videoId;
        mSabrProtocolErrorTimeMs = nowMs;
        failure.action("reload_video");
        mVideoLoaderController.reloadVideo();

        return true;
    }

    /**
     * The start hangs on a first segment that never came while the other track's did (see VideoStarts#isOneTrackStuck), the first
     * time for this video
     */
    private boolean isFirstStuckLoad() {
        Video video = getVideo();

        if (video == null || Helpers.equals(video.videoId, mStuckLoadVideoId) || !VideoStarts.isOneTrackStuck()) {
            return false;
        }

        mStuckLoadVideoId = video.videoId;

        return true;
    }

    /**
     * The connection to the server broke, or the response it sent did, not its answer (e.g. a read timeout, not a 403): another
     * client would stream from the same servers. Up to MAX_NETWORK_ERROR_RELOADS reloads a video, then the client is switched.
     */
    private boolean applyNetworkErrorReload(Throwable error, PlayerFailure failure) {
        Video video = getVideo();

        if (video == null || !(isConnectionError(error) || isBrokenSabrResponse(error))) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mNetworkErrorVideoId)) {
            mNetworkErrorVideoId = video.videoId;
            mNetworkErrorReloads = 0;
        }

        if (++mNetworkErrorReloads > MAX_NETWORK_ERROR_RELOADS) {
            return false;
        }

        failure.action("network_reload " + mNetworkErrorReloads + "/" + MAX_NETWORK_ERROR_RELOADS);

        return true;
    }

    /**
     * The SABR response read had bytes no part starts with (see SabrStream#nextKnownUMPPart): broken on the way or read out of
     * step, not the client's formats
     */
    private static boolean isBrokenSabrResponse(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownPartError) {
                return true;
            }
        }

        return false;
    }

    /**
     * The data source failed on the connection: an I/O error under it. An answer of the server (a response code, a content type)
     * has none. SABR wraps it in an IllegalStateException (see UMPDecoder).
     */
    private static boolean isConnectionError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSourceException) {
                return cause.getCause() instanceof IOException;
            }
        }

        return false;
    }

    private void applyEngineErrorAction(int type, int rendererIndex, Throwable error, PlayerFailure failure) {
        boolean restartEngine = true;
        long restartDelayMs = RETRY_MS;
        boolean showMessage = true;
        String errorContent = error != null ? error.getMessage() : null;
        String errorTitle = getErrorTitle(type, rendererIndex, error);
        String errorMessage = errorTitle + "\n" + errorContent;
        Log.e(TAG, errorMessage);

        if (Helpers.startsWithAny(errorContent, "Unable to connect to")) {
            // No internet connection or WRONG DATE on the device
            // Recently this message starting to show for other unknown reasons
            // Not while IPv6 doesn't connect: the player stays on OkHttp till the app restarts (see ExoMediaSourceFactory)
            if (!getPlayerTweaksData().isNetworkErrorFixingDisabled() && !OkHttpManager.isIpv6Disabled()) {
                failure.action("switch_engine:" + Utils.getNetworkEngineId(switchNextEngine()));
            }
        } else if (error instanceof OutOfMemoryError || (error != null && error.getCause() instanceof OutOfMemoryError)) {
            if (getPlayerTweaksData().getPlayerDataSource() == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP && !OkHttpManager.isIpv6Disabled()) {
                // OkHttp has memory leak problems
                if (enableFasterDataSource()) {
                    failure.action("switch_engine:" + Utils.getNetworkEngineId(Utils.getFasterDataSource()));
                }
            } else if (getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGH || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGHEST) {
                failure.action("lower_buffer");
                getPlayerData().setVideoBufferType(PlayerData.BUFFER_MEDIUM);
            } else {
                failure.action("lower_quality");
                lowerVideoQuality(); // NOTE: restart engine required
            }
        } else if (Helpers.containsAny(errorContent, "Exception in CronetUrlRequest") && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
            if (getVideo() != null && !getVideo().isLive) { // Finished live stream may provoke errors in Cronet
                failure.action("engine_default");
                getPlayerTweaksData().setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_SOURCE && rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN) {
            // NOTE: Starts with any (url deciphered incorrectly)
            // "Response code: 403" (poToken error, forbidden)
            // "Response code: 404" (not sure whether below helps)
            // "Response code: 503" (not sure whether below helps)
            // "Response code: 400" (not sure whether below helps)
            // "Response code: 429" (subtitle error, too many requests)
            // "Response code: 500" (subtitle error, generic server error)

            // NOTE: Fixing too many requests or network issues
            // NOTE: All these errors have unknown renderer (-1)
            // "Unable to connect to", "Invalid NAL length", "Response code: 421",
            // "Response code: 404", "Response code: 429", "Invalid integer size",
            // "Unexpected ArrayIndexOutOfBoundsException", "Unexpected IndexOutOfBoundsException"

            restartEngine = false;
            showMessage = false;

            boolean isGeneralError = Helpers.startsWithAny(errorContent, "Response code: 429", "Response code: 500");
            if (isGeneralError && isSubtitlesEnabled()) {
                failure.action("disable_subtitles_for_video");
                disableSubtitlesForVideo(); // Response code: 429
            } else if (isGeneralError && getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                failure.action("disable_high_bitrate");
                getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            } else if (!mBufferingDetector.isPlayable()) { // Response code: 403
                // The stream fails instantly if nParam isn't correct.
                // Note, nParam generation strictly tied to the client but some reported that OkHttp could help.
                failure.action("switch_next_client_now");
                YouTubeServiceManager.instance().switchNextClientNow();
                showMessage = true;
            } else if (!applyNetworkErrorReload(error, failure)) {
                // A broken connection (e.g. a read timeout) or SABR response is only reloaded, with the same client
                failure.action("switch_next_client");
                YouTubeServiceManager.instance().switchNextClient(); // Response code: 403
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_SUBTITLE) {
            // "Response code: 429" (subtitle error)
            // "Response code: 500" (subtitle error)
            failure.action("disable_subtitles_for_video");
            disableSubtitlesForVideo();
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO) {
            failure.action("temp_video_avc");
            getPlayerData().setTempVideoFormat(FormatItem.VIDEO_FHD_AVC_30);
            if (getPlayerTweaksData().isSWDecoderForced()) {
                failure.action("disable_sw_decoder");
                getPlayerTweaksData().setSWDecoderForced(false);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && error instanceof AudioSink.InitializationException) {
            // Whose refusal it is, why, and whether the device would take another config (see AudioOutputProbe)
            AudioSink.InitializationException refusal = (AudioSink.InitializationException) error;
            failure.detail("audioOutput", AudioOutputProbe.probe(getContext(), refusal.sampleRate, refusal.channelConfig,
                    refusal.bufferSize));

            // The device refused a new AudioTrack even after DefaultAudioSink retried (see AudioTrackInitRetry). Once, it can be the
            // app's own: a seek briefly holds two tracks. The format plays fine, so don't save another one. A reload frees the old
            // track; if this video keeps failing, restart the engine to free everything the player holds. Refused by the device
            // already (see applyDeviceAudioRefusal), a reload is no use.
            if (++mAudioInitErrorCount == 1 && !mIsAudioRefusedByDevice) {
                failure.action("audio_reload");
                restartEngine = false;
                showMessage = false;
            } else {
                // Or the device refuses this audio config (e.g. passthrough): go on with AAC until another video opens
                FormatItem audioFormat = getPlayer() != null ? getPlayer().getAudioFormat() : null;
                FormatItem fallback = applyAudioFallback(audioFormat != null && audioFormat.getTrack() != null ? audioFormat.getTrack().format : null);
                failure.action("audio_fallback:" + getFallbackName(fallback));

                if (fallback == null && AudioTrackCounts.getOpen() == 0) {
                    restartDelayMs = applyDeviceAudioRefusal(failure);
                    showMessage = false;
                } else if (fallback == null && mAudioInitErrorCount > MAX_AUDIO_INIT_RESTARTS && getVideo() != null) {
                    // Nothing left to change, and refused again: each restart waits longer for the device to have room for a track
                    // (FARAJV5R #17–#35: 40 restarts a minute for 10 minutes). Told once.
                    restartDelayMs = mAudioInitRetries.next(getVideo().videoId);
                    showMessage = mAudioInitRetries.getCount(getVideo().videoId) == 1;
                }
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO) {
            failure.action("audio_hq_mp4a");
            getPlayerData().setFormat(FormatItem.AUDIO_HQ_MP4A);
            restartEngine = false;
        } else if (error instanceof DecoderErrorException) {
            // The decoder failed even after the renderer re-initialized it. A restart may be enough, but once the video fails again it
            // goes on with a format most devices decode, until another video opens. The saved formats stay.
            if (++mDecoderErrorCount > 1) {
                failure.action("decoder_fallback:" + getFallbackName(applyDecoderErrorFallback((DecoderErrorException) error)));
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_UNEXPECTED) {
            if (error instanceof NullPointerException) {
                // SABR extractor throws NPE on subtitle error
                // NOTE: the engine should be restarted
                failure.action("disable_subtitles");
                disableSubtitles();
            } else if (Helpers.startsWithAny(errorContent,
                    "Buffer too small", "Invalid to call at Released state; only valid in executing state")) {
                // IllegalStateException: Buffer too small (5242880 < 7208383)
                // NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.
                // This will change current format to 'Disabled'. Do restartEngine() instead.
                failure.action("lower_quality");
                lowerVideoQuality(); // NOTE: restart engine required
            }
        }

        if (showMessage) {
            MessageHelpers.showLongMessage(getContext(), errorMessage);
            if (getPlayer() != null) {
                getPlayer().setTitle(errorContent);
            }
        }

        if (restartEngine) {
            failure.action(delayed("restart_engine", restartDelayMs));
            mVideoLoaderController.restartEngine((int) restartDelayMs);
        } else {
            // Need at least to reload the video because the player becomes idle after error
            failure.action("reload_video");
            mVideoLoaderController.reloadVideo();
        }
    }

    @SuppressLint("StringFormatMatches")
    private String getErrorTitle(int type, int rendererIndex, Throwable error) {
        String errorTitle;
        int msgResId;

        switch (type) {
            // Some ciphered data could be outdated.
            // Might happen when the app wasn't used quite a long time.
            case PlayerEventListener.ERROR_TYPE_SOURCE:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_source;
                        break;
                    default:
                        msgResId = R.string.unknown_source_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_RENDERER:
                if (error instanceof AudioSink.InitializationException) {
                    // Rarely a format problem (see applyEngineErrorAction)
                    errorTitle = getContext().getString(R.string.msg_player_error, type);
                    break;
                }
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_renderer;
                        break;
                    default:
                        msgResId = R.string.unknown_renderer_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_UNEXPECTED:
                errorTitle = getContext().getString(R.string.player_unexpected_error);
                break;
            default:
                errorTitle = getContext().getString(R.string.msg_player_error, type);
                break;
        }

        return errorTitle;
    }

    public void runFormatErrorAction(Throwable error) {
        if (getPlayer() == null) {
            return;
        }

        List<ClientAttempts.Attempt> attempts = getVideo() != null ? ClientAttempts.get(getVideo().videoId) : null;
        ClientAttempts.Attempt unplayable = getUnplayable(attempts);
        PlayerFailure failure = newFailure(unplayable != null ? PlayerFailure.KIND_UNPLAYABLE : PlayerFailure.KIND_FORMAT_ERROR)
                .error(error)
                .clientAttempts(attempts);

        // The start ends at the failed fetch, not when the user leaves (see VideoStarts#onFormatsFailed)
        if (getVideo() != null) {
            VideoStarts.onFormatsFailed(getVideo().videoId, unplayable != null);
        }

        try {
            if (unplayable != null) {
                failure.playability(unplayable.status, unplayable.reason);
                runUnplayableAction(unplayable, isMembers(attempts), failure);
            } else {
                runFormatErrorAction(error, attempts, failure);
            }
        } finally {
            failure.record();
        }
    }

    /**
     * Every client said the video can't be played (none answered otherwise): the first one's why. Null when one gave formats or
     * failed some other way (e.g. no network).
     */
    @Nullable
    private static ClientAttempts.Attempt getUnplayable(@Nullable List<ClientAttempts.Attempt> attempts) {
        if (attempts == null) {
            return null;
        }

        ClientAttempts.Attempt first = null;

        for (ClientAttempts.Attempt attempt : attempts) {
            if (ClientAttempts.RESULT_UNPLAYABLE.equals(attempt.result)) {
                if (first == null) {
                    first = attempt;
                }
            } else if (!ClientAttempts.RESULT_NULL_RESPONSE.equals(attempt.result)) {
                return null;
            }
        }

        return first;
    }

    /**
     * A client said only the channel's members watch it (see ClientAttempts.Attempt#members): the account isn't one
     */
    private static boolean isMembers(@Nullable List<ClientAttempts.Attempt> attempts) {
        if (attempts == null) {
            return false;
        }

        for (ClientAttempts.Attempt attempt : attempts) {
            if (attempt.members) {
                return true;
            }
        }

        return false;
    }

    /**
     * Its reason is shown, and it's tried again in a while: each second asked every client again (about 20 requests a second).
     * One only the channel's members watch isn't tried again: it won't play in a while either. It's remembered (see MembersVideos), and
     * the next video plays when it came next (autoplay, the Next button, see VideoLoaderController.skipMembersVideo). Its reason shows
     * till then: what's next may be known only once its metadata loaded.
     *
     * @param isMembers only the channel's members watch it (see isMembers)
     */
    private void runUnplayableAction(ClientAttempts.Attempt unplayable, boolean isMembers, PlayerFailure failure) {
        if (isEmbedPlayer()) {
            failure.action("finish_embed");
            getPlayer().finish();
            return;
        }

        String reason = unplayable.reason != null ? unplayable.reason : unplayable.status;
        Log.e(TAG, "Unplayable on every client: %s", reason);

        if (reason != null) {
            getPlayer().setTitle(reason);
        }

        getPlayer().showProgressBar(false);
        // The reason is the title, seen only with the controls: up, or the screen stays black. Nothing plays, so they don't hide
        getPlayer().showOverlay(true);

        if (isMembers) {
            Video video = getVideo();

            if (video != null) {
                MembersVideos.add(video.videoId);
                // The reason stays the title when its metadata loads (see below, VideoLoaderController.skipMembersVideo)
                video.syncUnplayable(reason);
            }

            if (mVideoLoaderController.skipMembersVideo(video)) {
                failure.action("members_next");
                return;
            }

            failure.action("members_stay");
            // Its suggestions, and the next video of the Next button, come with its metadata: that loads once a video plays (see
            // SuggestionsController.onVideoLoaded)
            SuggestionsController suggestions = getController(SuggestionsController.class);

            if (suggestions != null && video != null) {
                suggestions.loadSuggestions(video);
            }

            return;
        }

        failure.action("reload_video_" + UNPLAYABLE_RELOAD_MS / 1_000 + "s");
        mVideoLoaderController.reloadVideo(UNPLAYABLE_RELOAD_MS);
    }

    /**
     * @param attempts of the fetch that failed, each client's (see ClientAttempts)
     */
    private void runFormatErrorAction(Throwable error, @Nullable List<ClientAttempts.Attempt> attempts, PlayerFailure failure) {
        if (isEmbedPlayer()) {
            failure.action("finish_embed");
            getPlayer().finish();
            return;
        }

        String message = error.getMessage();
        String className = error.getClass().getSimpleName();
        String fullMsg = String.format("loadFormatInfo error: %s: %s", className, Utils.getStackTraceAsString(error));
        Log.e(TAG, fullMsg);
        String videoId = getVideo() != null ? getVideo().videoId : null;
        // Told by the failures themselves, not by what failed before: the fetch's error didn't connect or got no answer (e.g. FARAJV5R
        // #55: something else answered for youtube.com), or every client's attempt didn't (a refused one gives no exception, see
        // RetrofitHelper#takeConnectException). The poToken's WebView keeps what its request failed with (see
        // PoTokenWebView#keepInitError): a broken WebView isn't one that couldn't connect. Another client wouldn't help.
        boolean isNetworkDown = LoadErrors.isConnectionError(error) || isEveryAttemptUnconnected(attempts);

        if (!Helpers.containsAny(message, "fromNullable result is null")) {
            // Once a run, not each time it's tried again (a stack trace a second for 15 minutes)
            if (mFetchRetries.getCount(videoId) == 0) {
                MessageHelpers.showLongMessage(getContext(), fullMsg);
            }

            if (getPlayer() != null) {
                getPlayer().setTitle(fullMsg);
            }
        }

        // A name that didn't resolve ("No address associated with hostname") is one of these, as the network's: the DNS isn't switched
        // to IPv4 and the app restarted for it anymore. Every DNS type ends in the system's lookup, which had just failed (IPv4 is the
        // system's, its addresses reordered; Google's falls back to it), and the DNS is read at each lookup.
        if (isNetworkDown) {
            failure.action("network_wait");
            reloadLater(failure, mFetchRetries.next(videoId));
        } else if (Helpers.containsAny(message, "Unexpected token", "Syntax error", "invalid argument") || // temporal fix
                Helpers.equalsAny(className, "PoTokenException", "BadWebViewException")) {
            failure.action("switch_next_client").action("reload_video");
            YouTubeServiceManager.instance().switchNextClient();
            mVideoLoaderController.reloadVideo();
        } else if (Helpers.containsAny(message, "is not defined")) {
            failure.action("invalidate_cache").action("reload_video");
            YouTubeServiceManager.instance().invalidateCache();
            mVideoLoaderController.reloadVideo();
        } else {
            Log.e(TAG, "Probably no internet connection");
            reloadLater(failure, mFetchRetries.next(videoId));
        }
    }

    /**
     * Each client tried failed on its connection (see ClientAttempts.Attempt#connectionError): none got an answer
     */
    private static boolean isEveryAttemptUnconnected(@Nullable List<ClientAttempts.Attempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return false;
        }

        for (ClientAttempts.Attempt attempt : attempts) {
            if (attempt.connectionError == null) {
                return false;
            }
        }

        return true;
    }

    /**
     * The video loaded again, after the wait (see RetryBackoff)
     */
    private void reloadLater(PlayerFailure failure, long delayMs) {
        failure.action(delayed("reload_video", delayMs));
        mVideoLoaderController.reloadVideo((int) delayMs);
    }

    /**
     * For the failure's actions: e.g. "reload_video", "reload_video_4s" for a wait longer than the usual one (see RETRY_MS)
     */
    private static String delayed(String action, long delayMs) {
        return delayMs > RETRY_MS ? action + "_" + delayMs / 1_000 + "s" : action;
    }

    /**
     * The DNS the DNS fix switched from (see mDnsFixFrom) is put back, and the fix isn't tried again. A lookup reads it, so the reload
     * after this applies it.
     */
    private void undoDnsFix(PlayerFailure failure) {
        if (mDnsFixFrom < 0) {
            return;
        }

        failure.action("dns_revert");
        getPlayerTweaksData().setPreferredDnsType(mDnsFixFrom);
        mDnsFixFrom = -1;
        mIsDnsFixRefuted = true;
    }

    /**
     * Bad idea. Faster source is different among devices
     *
     * @return it wasn't on
     */
    private boolean enableFasterDataSource() {
        if (isFasterDataSourceEnabled()) {
            return false;
        }

        getPlayerTweaksData().setPlayerDataSource(Utils.getFasterDataSource());

        return true;
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private boolean isFasterDataSourceEnabled() {
        int fasterDataSource = Utils.getFasterDataSource();
        return getPlayerTweaksData().getPlayerDataSource() == fasterDataSource;
    }

    /**
     * @return the engine switched to
     */
    private int switchNextEngine() {
        int nextEngine = getNextEngine();
        getPlayerTweaksData().setPlayerDataSource(nextEngine);

        return nextEngine;
    }

    private int getNextEngine() {
        int currentEngine = getPlayerTweaksData().getPlayerDataSource();
        Integer[] engineList = Utils.skipCronet() ?
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP } :
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP };
        return Helpers.getNextValue(engineList, currentEngine);
    }

    private boolean isSubtitlesEnabled() {
        return getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(getPlayer().getSubtitleFormat());
    }

    private void disableSubtitles() {
        getPlayerData().setSubtitlesPerChannelEnabled(false); // Important!
        getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }

    /**
     * Off for the video only: a 429 (too many requests) or a 500 is their server's, for now. They were turned off in the settings, and
     * one rate limit left the user without subtitles from then on. Its reloads play without them (see
     * VideoStateController#restoreSubtitleFormat), the next video with the user's.
     */
    private void disableSubtitlesForVideo() {
        Video video = getVideo();
        getPlayerData().setSubtitlesOffFor(video != null ? video.videoId : null);

        if (getPlayer() != null) {
            getPlayer().setFormat(FormatItem.SUBTITLE_NONE);
        }
    }

    private boolean isStreamEnded() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return getVideo().isLiveEnd && getPlayer().getDurationMs() > 0
                && getPlayer().getDurationMs() - getPlayer().getPositionMs() < STREAM_END_THRESHOLD_MS;
    }

    /**
     * A video (not a live stream) that played, stalled at its end: it ended. One that never played is stuck at its start
     */
    private boolean isAtVideoEnd() {
        if (getPlayer() == null || getVideo() == null || getVideo().isLive || !mBufferingDetector.isPlayable()) {
            return false;
        }

        long durationMs = getPlayer().getDurationMs();

        return durationMs > 0 && durationMs - getPlayer().getPositionMs() < VIDEO_END_THRESHOLD_MS;
    }

    /**
     * The video streams over SABR, and the server's last word on its poToken, since the source opened, is that the attestation is
     * pending (see ProtectionStatuses). Over another protocol, or told before, that word was of a stream before (e.g. the client
     * switched to VISIONOS's DASH, or to another SABR client whose requests got no answer yet).
     */
    private boolean isAttestationPending() {
        Video video = getVideo();

        return video != null && PlayerFailure.PROTOCOL_SABR.equals(mVideoLoaderController.getFormatProtocol())
                && ProtectionStatuses.isAttestationPending(video.videoId, mSourceChangedMs);
    }

    private boolean isOfflineVideo() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return !getVideo().isLive && !getVideo().isLiveEnd;
    }

    /**
     * NOTE: Applied with restartEngine(). reloadVideo() resets the temp formats, so a reload of the same video applies it again
     * (see onNewVideo).
     */
    private FormatItem applyDecoderErrorFallback(DecoderErrorException error) {
        FormatItem fallback = null;

        if (error.trackType == C.TRACK_TYPE_VIDEO) {
            fallback = DecoderErrorFallback.getVideoFormat(error.format);

            if (fallback != null) {
                mVideoFallback = fallback;
                getPlayerData().setTempVideoFormat(fallback);
            }
        } else if (error.trackType == C.TRACK_TYPE_AUDIO) {
            fallback = applyAudioFallback(error.format);
        }

        // Null: nothing left to fall back to, the restart is all there is
        Log.e(TAG, "Decoder error fallback: %s", fallback != null ? fallback.getTrack().format : null);

        return fallback;
    }

    /**
     * AAC in the language of the failed track for the video in mDecoderErrorVideoId, unless the failed format is AAC already.<br/>
     * NOTE: Applied with restartEngine() (see applyDecoderErrorFallback).
     */
    private FormatItem applyAudioFallback(Format failed) {
        FormatItem audioFormat = getPlayerData().getFormat(FormatItem.TYPE_AUDIO);
        // The language of the failed track: the format picked for this video isn't saved
        String language = failed != null && failed.language != null ? failed.language
                : audioFormat != null ? audioFormat.getLanguage() : null;
        FormatItem fallback = DecoderErrorFallback.getAudioFormat(failed, language);

        if (fallback != null) {
            mAudioFallback = fallback;
            getPlayerData().setTempAudioFormat(fallback);
        }

        return fallback;
    }

    /**
     * Refused again with no AudioTrack of the app's open, and no other audio format to try: it's the device's, not the app's memory.
     * A Shield refused every 44.1 kHz track for 20 minutes and more, a new process's first one too, and played again only once the
     * device was restarted (FARAJV5R #40–#41: a process 110 s old, the device booted at 07:53:57; #104: booted 23 s after it;
     * 9M6NBWN7: a process 18.7 s old). No restart of the player helps: the user is told once to restart the device, and it's tried
     * again with a wait growing across the videos, till a video's sound plays.
     *
     * @return the wait before the engine is restarted
     */
    private long applyDeviceAudioRefusal(PlayerFailure failure) {
        failure.action("device_audio_refused");

        if (!mIsAudioRefusedByDevice) {
            mIsAudioRefusedByDevice = true;
            MessageHelpers.showLongMessage(getContext(), R.string.msg_device_refuses_audio);
        }

        if (getPlayer() != null && getContext() != null) {
            // In place of the error's title: what the user can do about it
            getPlayer().setTitle(getContext().getString(R.string.msg_device_refuses_audio));
        }

        return mDeviceAudioRetries.next(DEVICE_AUDIO_RETRY_KEY);
    }

    /**
     * For the failure's actions, e.g. "avc1"
     */
    private static String getFallbackName(FormatItem fallback) {
        Format format = fallback != null && fallback.getTrack() != null ? fallback.getTrack().format : null;

        if (format == null) {
            return "none";
        }

        return format.codecs != null ? format.codecs : String.valueOf(format.sampleMimeType);
    }

    /**
     * The video, where the player is at, its formats and where they're from
     */
    private PlayerFailure newFailure(String kind) {
        PlayerFailure failure = PlayerFailure.create(getContext(), kind).video(getVideo());

        if (mVideoLoaderController != null) {
            failure.source(mVideoLoaderController.getFormatClient(), mVideoLoaderController.getFormatProtocol());
            mVideoLoaderController.addPickedClient(failure);
        }

        if (getPlayer() != null) {
            failure.playback(getPlayer().getPositionMs(), getPlayer().getDurationMs(), mBufferingDetector.isPlayable());

            try {
                // The player's are the video before's till this one's reached it (e.g. its fetch failed)
                if (mVideoLoaderController == null || mVideoLoaderController.isFormatOfVideo()) {
                    failure.formats(getPlayer().getVideoFormat(), getPlayer().getAudioFormat());
                }
            } catch (RuntimeException e) {
                // The engine may be released already: the fix goes on without them
                Log.e(TAG, "Can't read the formats of the failure: %s", e.getMessage());
            }
        }

        return failure;
    }

    private void reapplyDecoderErrorFallback() {
        if (mVideoFallback != null) {
            getPlayerData().setTempVideoFormat(mVideoFallback);
        }

        if (mAudioFallback != null) {
            getPlayerData().setTempAudioFormat(mAudioFallback);
        }
    }

    /**
     * NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.<br/>
     * This will change current format to 'Disabled'. Do reloadEngine() instead.
     */
    private boolean lowerVideoQuality() {
        if (getPlayer() == null) {
            return false;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();
        FormatItem videoFormat = getPlayer().getVideoFormat();

        if (videoFormats == null || videoFormat == null) {
            return false;
        }

        int size = Math.max(videoFormat.getWidth(), videoFormat.getHeight());

        // Limit by 720p
        if (size <= 1280) {
            return false;
        }

        // The next size down: another codec of the same size wouldn't help. The first of it, as the list prefers.
        FormatItem lower = null;

        for (FormatItem format : videoFormats) {
            int formatSize = Math.max(format.getWidth(), format.getHeight());

            if (formatSize > 0 && formatSize < size && (lower == null || formatSize > Math.max(lower.getWidth(), lower.getHeight()))) {
                lower = format;
            }
        }

        String codec = getCodecName(lower);

        if (codec == null) {
            return false;
        }

        // A limit, as the decoder error fallback's, not the track: the track list is the stream's, and a reload makes another one. The
        // fps is a limit too, so 30 fps videos match.
        FormatItem limit = ExoFormatItem.fromVideoSpec(String.format(Locale.US, "%d,%d,60,%s", lower.getWidth(), lower.getHeight(), codec),
                false);

        if (limit == null) {
            return false;
        }

        // This helps to persist the format between engine restart
        getPlayerData().setTempVideoFormat(limit);
        // And a reload of the video (see onNewVideo). Till another video.
        mVideoFallback = limit;

        return true;
    }

    @Nullable
    private static String getCodecName(FormatItem format) {
        Format trackFormat = format.getTrack() != null ? format.getTrack().format : null;

        return trackFormat != null && trackFormat.codecs != null ? TrackSelectorUtil.codecNameShort(trackFormat.codecs) : null;
    }

    /**
     * The video stalled long not long after the last time
     */
    private boolean isRepeatedStall() {
        String videoId = getVideo() != null ? getVideo().videoId : null;
        long nowMs = SystemClock.elapsedRealtime();
        boolean isRepeated = videoId != null && videoId.equals(mStallVideoId) && nowMs - mStallTimeMs <= REPEATED_STALL_MS;
        mStallVideoId = videoId;
        mStallTimeMs = nowMs;

        return isRepeated;
    }
}
