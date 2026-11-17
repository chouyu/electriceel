package com.google.android.exoplayer2.audio;

import androidx.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartTube fix: the {@link android.media.AudioTrack}s the players of the app's process built and released, for a refused one to be
 * told apart: tracks the app still holds (its share of AudioFlinger's memory, 10 MB per app on an Nvidia Shield, is full of them), or
 * a device that refuses whatever the app holds. A Shield refused every track for 20 minutes and more, a new process's first one too,
 * and played again only once the device was restarted (FARAJV5R #40–#41, 9M6NBWN7): refused with none open, it's the device's (see
 * ErrorFixerController#applyDeviceAudioRefusal).
 */
public final class AudioTrackCounts {

  /** Told of each track built, refused and failed, e.g. to count them since the device booted. Called on the player's threads. */
  public interface Listener {

    void onBuilt();

    void onRefused();

    void onFailed();
  }

  private static final AtomicInteger built = new AtomicInteger();
  private static final AtomicInteger refused = new AtomicInteger();
  private static final AtomicInteger failed = new AtomicInteger();
  private static final AtomicInteger releasing = new AtomicInteger();
  private static final AtomicInteger released = new AtomicInteger();
  @Nullable private static volatile Listener listener;

  private AudioTrackCounts() {}

  /** One listener, null for none. */
  public static void setListener(@Nullable Listener newListener) {
    listener = newListener;
  }

  /** One was built and initialized. */
  /* package */ static void onBuilt() {
    built.incrementAndGet();
    Listener current = listener;
    if (current != null) {
      current.onBuilt();
    }
  }

  /** The device refused one (see AudioSink.InitializationException): it was released at once. */
  /* package */ static void onRefused() {
    refused.incrementAndGet();
    Listener current = listener;
    if (current != null) {
      current.onRefused();
    }
  }

  /** Refused past the retries (see AudioTrackInitRetry): the player is told. */
  /* package */ static void onFailed() {
    failed.incrementAndGet();
    Listener current = listener;
    if (current != null) {
      current.onFailed();
    }
  }

  /** One is let go: its release runs on a thread of its own, as it can take a while. */
  /* package */ static void onReleaseStarted() {
    releasing.incrementAndGet();
  }

  /** One that was let go is released. */
  /* package */ static void onReleased() {
    releasing.decrementAndGet();
    released.incrementAndGet();
  }

  /** Built since the process started. */
  public static int getBuilt() {
    return built.get();
  }

  /** Refused since the process started, each attempt. */
  public static int getRefused() {
    return refused.get();
  }

  /** Refused past the retries since the process started: each a player's error. */
  public static int getFailed() {
    return failed.get();
  }

  /** Built and not released yet, the ones being released included. */
  public static int getOpen() {
    return built.get() - released.get();
  }

  /** Let go and not released yet. */
  public static int getReleasing() {
    return releasing.get();
  }
}
