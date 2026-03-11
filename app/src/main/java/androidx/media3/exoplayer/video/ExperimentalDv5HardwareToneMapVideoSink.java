/*
 * Copyright 2026 Nexio
 */
package androidx.media3.exoplayer.video;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.ExoPlaybackException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Experimental VideoSink for Shield DV5 hardware decode + native tone mapping.
 *
 * <p>MediaCodec decodes into an ImageReader surface. Frames are then forwarded to native ffmpeg JNI
 * where external RPU-matched metadata is consumed and libplacebo tone mapping is applied before
 * rendering to the target output surface.
 */
@UnstableApi
public final class ExperimentalDv5HardwareToneMapVideoSink implements VideoSink {
  private static final String TAG = "Dv5HwToneMapSink";
  private static final int MAX_IMAGES = 5;
  // ImageFormat.YCBCR_P010 value, kept inline for API/SDK compatibility.
  private static final int IMAGE_FORMAT_YCBCR_P010 = 0x36;
  private static final long MIN_EARLY_US_LATE_THRESHOLD = -30_000;
  private static final long FORCE_RENDER_AFTER_US = 100_000;
  private static final long CPU_FALLBACK_IMAGE_READER_USAGE =
      HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN;

  private enum InputReaderMode {
    GPU_OPTIMIZED,
    CPU_READBACK_FALLBACK
  }

  private final Object lock;
  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster;
  private final VideoFrameRenderControl videoFrameRenderControl;
  private final Queue<VideoFrameHandler> videoFrameHandlers;
  private final Object imageDrainLock;
  private final Context applicationContext;
  private final long allowedJoiningTimeMs;
  private final boolean forceCpuReadableFallback;

  @Nullable private HandlerThread imageReaderThread;
  @Nullable private Handler imageReaderHandler;
  @Nullable private ImageReader inputImageReader;
  @Nullable private Surface inputSurface;
  @Nullable private Surface outputSurface;
  private Size outputResolution;
  private Format inputFormat;
  private long streamStartPositionUs;
  private long bufferTimestampAdjustmentUs;
  private Listener listener;
  private Executor listenerExecutor;
  private VideoFrameMetadataListener videoFrameMetadataListener;
  private boolean firstFrameRendered;
  private boolean loggedPurePathFallback;
  private boolean loggedRuntimePathMarker;
  private boolean isDrainingDecodedImages;
  private InputReaderMode inputReaderMode;

  public ExperimentalDv5HardwareToneMapVideoSink(
      Context context, long allowedJoiningTimeMs, boolean forceCpuReadableFallback) {
    this.applicationContext = context.getApplicationContext();
    this.allowedJoiningTimeMs = allowedJoiningTimeMs;
    this.forceCpuReadableFallback = forceCpuReadableFallback;
    this.lock = new Object();
    this.videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            this.applicationContext,
            new FrameTimingEvaluatorImpl(),
            allowedJoiningTimeMs);
    this.videoFrameReleaseEarlyTimeForecaster = new VideoFrameReleaseEarlyTimeForecaster(1f);
    this.videoFrameReleaseControl.setClock(Clock.DEFAULT);
    this.videoFrameRenderControl =
        new VideoFrameRenderControl(
            new FrameRendererImpl(), videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);
    this.videoFrameHandlers = new ArrayDeque<>();
    this.imageDrainLock = new Object();
    this.outputResolution = Size.UNKNOWN;
    this.inputFormat = new Format.Builder().build();
    this.streamStartPositionUs = C.TIME_UNSET;
    this.bufferTimestampAdjustmentUs = 0L;
    this.listener = Listener.NO_OP;
    this.listenerExecutor = runnable -> {};
    this.videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {};
    this.firstFrameRendered = false;
    this.loggedPurePathFallback = false;
    this.loggedRuntimePathMarker = false;
    this.isDrainingDecodedImages = false;
    this.inputReaderMode = InputReaderMode.GPU_OPTIMIZED;
  }

  @Override
  public void startRendering() {
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameReleaseControl.onStarted();
  }

  @Override
  public void stopRendering() {
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameReleaseControl.onStopped();
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    this.listener = listener;
    this.listenerExecutor = executor;
  }

  @Override
  public boolean initialize(Format sourceFormat) {
    inputFormat = sourceFormat;
    int width = sourceFormat.width > 0 ? sourceFormat.width : Format.NO_VALUE;
    int height = sourceFormat.height > 0 ? sourceFormat.height : Format.NO_VALUE;
    if (width == Format.NO_VALUE || height == Format.NO_VALUE) {
      return true;
    }
    return ensureInputReader(width, height);
  }

  @Override
  public boolean isInitialized() {
    synchronized (lock) {
      return inputSurface != null;
    }
  }

  @Override
  public void redraw() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush(boolean resetPosition) {
    if (resetPosition) {
      videoFrameReleaseControl.reset();
    }
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameRenderControl.flush();
    synchronized (lock) {
      videoFrameHandlers.clear();
    }
    drainDecodedImages();
  }

  @Override
  public boolean isReady(boolean otherwiseReady) {
    return videoFrameReleaseControl.isReady(otherwiseReady);
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    videoFrameRenderControl.signalEndOfInput();
  }

  @Override
  public void signalEndOfInput() {
    // Ignored.
  }

  @Override
  public boolean isEnded() {
    return videoFrameRenderControl.isEnded();
  }

  @Override
  public Surface getInputSurface() {
    synchronized (lock) {
      return checkNotNull(inputSurface);
    }
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    videoFrameReleaseControl.setPlaybackSpeed(speed);
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
    this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    this.outputSurface = outputSurface;
    this.outputResolution = outputResolution;
    this.firstFrameRendered = false;
    this.loggedPurePathFallback = false;
    videoFrameReleaseControl.setOutputSurface(outputSurface);
  }

  @Override
  public void clearOutputSurfaceInfo() {
    outputSurface = null;
    outputResolution = Size.UNKNOWN;
    firstFrameRendered = false;
    loggedPurePathFallback = false;
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
  }

  @Override
  public void setChangeFrameRateStrategy(@C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
    videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy);
  }

  @Override
  public void onInputStreamChanged(
      @InputType int inputType,
      Format format,
      long startPositionUs,
      @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
      List<Effect> videoEffects) {
    checkState(videoEffects.isEmpty());
    if (inputType != INPUT_TYPE_SURFACE) {
      throw new IllegalStateException("Only surface input is supported");
    }
    if (format.width > 0
        && format.height > 0
        && (format.width != inputFormat.width || format.height != inputFormat.height)) {
      ensureInputReader(format.width, format.height);
      videoFrameRenderControl.onVideoSizeChanged(format.width, format.height);
    } else if (format.width > 0 && format.height > 0 && inputFormat.width <= 0) {
      ensureInputReader(format.width, format.height);
      videoFrameRenderControl.onVideoSizeChanged(format.width, format.height);
    }
    if (format.frameRate != inputFormat.frameRate) {
      videoFrameReleaseControl.setFrameRate(format.frameRate);
    }
    inputFormat = format;
    if (startPositionUs != streamStartPositionUs) {
      videoFrameRenderControl.onStreamChanged(firstFrameReleaseInstruction, startPositionUs);
      streamStartPositionUs = startPositionUs;
    }
  }

  @Override
  public void allowReleaseFirstFrameBeforeStarted() {
    videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted();
  }

  @Override
  public boolean handleInputFrame(long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
    synchronized (lock) {
      videoFrameHandlers.add(videoFrameHandler);
    }
    long framePresentationTimeUs = bufferPresentationTimeUs + bufferTimestampAdjustmentUs;
    videoFrameRenderControl.onFrameAvailableForRendering(framePresentationTimeUs);
    listenerExecutor.execute(() -> listener.onFrameAvailableForRendering());
    return true;
  }

  @Override
  public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
    try {
      videoFrameRenderControl.render(positionUs, elapsedRealtimeUs);
      safeDrainDecodedImages("render");
    } catch (ExoPlaybackException e) {
      throw new VideoSinkException(e, inputFormat);
    }
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    videoFrameReleaseControl.join(renderNextFrameImmediately);
  }

  @Override
  public void release() {
    synchronized (lock) {
      videoFrameHandlers.clear();
      releaseInputReaderLocked();
      if (imageReaderThread != null) {
        imageReaderThread.quitSafely();
        imageReaderThread = null;
      }
      imageReaderHandler = null;
      isDrainingDecodedImages = false;
    }
  }

  private boolean ensureInputReader(int width, int height) {
    if (width <= 0 || height <= 0) {
      return false;
    }
    InputReaderMode desiredMode =
        forceCpuReadableFallback
            ? InputReaderMode.CPU_READBACK_FALLBACK
            : InputReaderMode.GPU_OPTIMIZED;
    synchronized (lock) {
      if (inputImageReader != null
          && inputImageReader.getWidth() == width
          && inputImageReader.getHeight() == height
          && inputReaderMode == desiredMode) {
        return true;
      }
      releaseInputReaderLocked();
      if (imageReaderThread == null) {
        imageReaderThread = new HandlerThread("dv5-hw-tone-map-image-reader");
        imageReaderThread.start();
        imageReaderHandler = new Handler(imageReaderThread.getLooper());
      }
      if (desiredMode == InputReaderMode.CPU_READBACK_FALLBACK) {
        if (Build.VERSION.SDK_INT >= 29) {
          inputImageReader =
              createImageReaderWithUsage(
                  width,
                  height,
                  ImageFormat.YUV_420_888,
                  CPU_FALLBACK_IMAGE_READER_USAGE,
                  "YUV_420_888+CPU_READ");
          if (inputImageReader == null) {
            inputImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES);
          }
        } else {
          inputImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES);
        }
        inputReaderMode = InputReaderMode.CPU_READBACK_FALLBACK;
      } else if (Build.VERSION.SDK_INT >= 29) {
        inputImageReader =
            createImageReaderWithUsage(
                width,
                height,
                IMAGE_FORMAT_YCBCR_P010,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                "YCBCR_P010");
        if (inputImageReader == null) {
          inputImageReader =
              createImageReaderWithUsage(
                  width,
                  height,
                  ImageFormat.PRIVATE,
                  HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                  "PRIVATE");
        }
        inputReaderMode = InputReaderMode.GPU_OPTIMIZED;
      } else {
        inputImageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_IMAGES);
        inputReaderMode = InputReaderMode.GPU_OPTIMIZED;
      }
      if (inputImageReader == null) {
        return false;
      }
      inputImageReader.setOnImageAvailableListener(
          reader -> safeDrainDecodedImages("image-listener"), checkNotNull(imageReaderHandler));
      inputSurface = inputImageReader.getSurface();
      maybeLogRuntimePathMarkerLocked();
      return true;
    }
  }

  private void maybeLogRuntimePathMarkerLocked() {
    if (loggedRuntimePathMarker || inputImageReader == null) {
      return;
    }
    String modeName =
        inputReaderMode == InputReaderMode.CPU_READBACK_FALLBACK
            ? "CPU_READBACK"
            : "GPU_OPTIMIZED";
    int imageFormat = inputImageReader.getImageFormat();
    Log.i(
        TAG,
        "DV5_HW_RENDER: runtimePath="
            + modeName
            + " format="
            + imageFormatToString(imageFormat)
            + "("
            + imageFormat
            + ") "
            + "size="
            + inputImageReader.getWidth()
            + "x"
            + inputImageReader.getHeight()
            + " forceCpuFallback="
            + forceCpuReadableFallback);
    loggedRuntimePathMarker = true;
  }

  private static String imageFormatToString(int format) {
    if (format == IMAGE_FORMAT_YCBCR_P010) {
      return "YCBCR_P010";
    }
    switch (format) {
      case ImageFormat.YUV_420_888:
        return "YUV_420_888";
      case ImageFormat.PRIVATE:
        return "PRIVATE";
      default:
        return "UNKNOWN";
    }
  }

  @Nullable
  private ImageReader createImageReaderWithUsage(
      int width, int height, int format, long usage, String formatName) {
    try {
      ImageReader reader = ImageReader.newInstance(width, height, format, MAX_IMAGES, usage);
      Log.i(TAG, "DV5_HW_RENDER: configured ImageReader format=" + formatName);
      return reader;
    } catch (RuntimeException exception) {
      Log.w(TAG, "DV5_HW_RENDER: ImageReader format " + formatName + " unavailable", exception);
      return null;
    }
  }

  private void releaseInputReaderLocked() {
    if (inputImageReader != null) {
      inputImageReader.setOnImageAvailableListener(null, null);
      inputImageReader.close();
      inputImageReader = null;
    }
    if (inputSurface != null) {
      inputSurface.release();
      inputSurface = null;
    }
  }

  private void drainDecodedImages() {
    synchronized (imageDrainLock) {
      if (isDrainingDecodedImages) {
        return;
      }
      isDrainingDecodedImages = true;
    }
    try {
      drainDecodedImagesInternal();
    } finally {
      synchronized (imageDrainLock) {
        isDrainingDecodedImages = false;
      }
    }
  }

  private void safeDrainDecodedImages(String source) {
    try {
      drainDecodedImages();
    } catch (RuntimeException exception) {
      Log.w(TAG, "DV5_HW_RENDER: drain failed source=" + source, exception);
    }
  }

  private void drainDecodedImagesInternal() {
    ImageReader imageReader;
    Surface currentOutputSurface = outputSurface;
    Size currentOutputResolution = outputResolution;
    synchronized (lock) {
      imageReader = inputImageReader;
    }
    if (imageReader == null || currentOutputSurface == null) {
      return;
    }
    while (true) {
      final Image image;
      try {
        image = imageReader.acquireNextImage();
      } catch (RuntimeException exception) {
        Log.w(TAG, "DV5_HW_RENDER: acquireNextImage failed", exception);
        return;
      }
      if (image == null) {
        return;
      }
      long presentationTimeUs =
          image.getTimestamp() > 0 ? image.getTimestamp() / 1_000L : C.TIME_UNSET;
      boolean rendered = false;
      HardwareBuffer hardwareBuffer = null;
      try {
        if (Build.VERSION.SDK_INT >= 29) {
          hardwareBuffer = image.getHardwareBuffer();
          if (hardwareBuffer != null) {
            int targetWidth =
                currentOutputResolution.getWidth() > 0
                    ? currentOutputResolution.getWidth()
                    : image.getWidth();
            int targetHeight =
                currentOutputResolution.getHeight() > 0
                    ? currentOutputResolution.getHeight()
                    : image.getHeight();
            rendered =
                FfmpegLibrary.renderExperimentalDv5HardwareFramePure(
                    presentationTimeUs, hardwareBuffer, targetWidth, targetHeight, currentOutputSurface);
            if (!rendered) {
              if (!loggedPurePathFallback) {
                loggedPurePathFallback = true;
                Log.i(TAG, "DV5_HW_RENDER: pure native renderer unavailable; using FFmpeg fallback");
              }
              rendered =
                  FfmpegLibrary.renderExperimentalDv5HardwareFrame(
                      presentationTimeUs,
                      hardwareBuffer,
                      targetWidth,
                      targetHeight,
                      currentOutputSurface);
            }
          }
        }
      } catch (Throwable throwable) {
        Log.w(TAG, "DV5_HW_RENDER: native render failed", throwable);
      } finally {
        if (hardwareBuffer != null && Build.VERSION.SDK_INT >= 29) {
          hardwareBuffer.close();
        }
        image.close();
      }

      if (rendered && !firstFrameRendered) {
        firstFrameRendered = true;
        listenerExecutor.execute(() -> listener.onFirstFrameRendered());
      } else if (!rendered) {
        listenerExecutor.execute(() -> listener.onFrameDropped());
      }
    }
  }

  private final class FrameRendererImpl implements VideoFrameRenderControl.FrameRenderer {
    @Nullable private Format outputFormat;

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      outputFormat =
          new Format.Builder()
              .setWidth(videoSize.width)
              .setHeight(videoSize.height)
              .setSampleMimeType(MimeTypes.VIDEO_RAW)
              .build();
      listenerExecutor.execute(() -> listener.onVideoSizeChanged(videoSize));
    }

    @Override
    public void renderFrame(long renderTimeNs, long framePresentationTimeUs, boolean isFirstFrame) {
      Format format = outputFormat == null ? new Format.Builder().build() : outputFormat;
      videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
          framePresentationTimeUs, renderTimeNs, format, /* mediaFormat= */ null);
      VideoFrameHandler videoFrameHandler;
      synchronized (lock) {
        videoFrameHandler = videoFrameHandlers.poll();
      }
      if (videoFrameHandler == null) {
        Log.w(TAG, "DV5_HW_RENDER: missing frame handler during render");
        return;
      }
      videoFrameHandler.render(renderTimeNs);
    }

    @Override
    public void dropFrame() {
      listenerExecutor.execute(() -> listener.onFrameDropped());
      VideoFrameHandler videoFrameHandler;
      synchronized (lock) {
        videoFrameHandler = videoFrameHandlers.poll();
      }
      if (videoFrameHandler == null) {
        Log.w(TAG, "DV5_HW_RENDER: missing frame handler during drop");
        return;
      }
      videoFrameHandler.skip();
    }
  }

  private static final class FrameTimingEvaluatorImpl
      implements VideoFrameReleaseControl.FrameTimingEvaluator {
    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return earlyUs < MIN_EARLY_US_LATE_THRESHOLD && elapsedSinceLastReleaseUs > FORCE_RENDER_AFTER_US;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return earlyUs < MIN_EARLY_US_LATE_THRESHOLD && !isLastFrame;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped)
        throws ExoPlaybackException {
      return false;
    }
  }
}
