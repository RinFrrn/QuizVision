package com.virin.visionquiz;

/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.virin.visionquiz.preference.PreferenceUtils;
import com.virin.visionquiz.screendetector.ScreenDetectorSession;
import com.virin.visionquiz.vision.graphic.GraphicOverlay;

import org.loka.screensharekit.EncodeBuilder;
import org.loka.screensharekit.ErrorInfo;
import org.loka.screensharekit.ScreenShareKit;
import org.loka.screensharekit.callback.ErrorCallBack;
import org.loka.screensharekit.callback.RGBACallBack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics or
 * displaying extra information). This receives preview frames from the camera at a specified rate,
 * sending those frames to child classes' detectors / classifiers as fast as it is able to process.
 */
    public class ScreenSource {

    private static final String TAG = "MIDemoApp:ScreenSource";
    private static final int OVERLAY_MASK_PADDING_DP = 12;

    protected Activity activity;

    private EncodeBuilder screenShareKit;

    private final GraphicOverlay graphicOverlay;
    private final ScreenFrameChangeDetector frameChangeDetector =
            new ScreenFrameChangeDetector();
    private final boolean screenChangeDetectionEnabled;

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private Thread processingThread;

    private final FrameProcessingRunnable processingRunnable;
    private final Object processorLock = new Object();

    private VisionImageProcessor frameProcessor;
    private int latestWidth = -1;
    private int latestHeight = -1;
    private boolean started = false;
    private final int overlayMaskPaddingPx;
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private final AtomicBoolean processorCallDispatched = new AtomicBoolean(false);
    private final AtomicInteger activeScanGeneration = new AtomicInteger();

    public ScreenSource(Activity activity) {
        this.activity = activity;
        overlayMaskPaddingPx =
                Math.round(OVERLAY_MASK_PADDING_DP * activity.getResources().getDisplayMetrics().density);
        screenChangeDetectionEnabled =
                PreferenceUtils.shouldDetectScreenChangesBeforeSearch(activity);
        graphicOverlay = new GraphicOverlay(activity, (AttributeSet) null);
        graphicOverlay.clear();
        processingRunnable = new FrameProcessingRunnable();
    }

    // ==============================================================================================
    // Public
    // ==============================================================================================

    /**
     * Stops the camera and releases the resources of the camera and underlying detector.
     */
    public void release() {
        synchronized (processorLock) {
            stop();
            cleanScreen();

            if (frameProcessor != null) {
                frameProcessor.stop();
            }
        }
    }

    /**
     * Opens the camera and starts sending preview frames to the underlying detector. The preview
     * frames are not displayed.
     *
     * @throws IOException if the camera's preview texture or display could not be initialized
     */
    public synchronized ScreenSource start() throws IOException {
        if (started) {
            resume();
            return this;
        }
        Log.e(TAG, "start");
        frameChangeDetector.reset();
        Point captureSize = getNativeCaptureSize();
        int frameRate = PreferenceUtils.getScreenCaptureFrameRate(activity);
        screenShareKit = ScreenShareKit.INSTANCE.init((FragmentActivity) activity);
        screenShareKit.config(
                captureSize.x,
                captureSize.y,
                frameRate,
                0,
                EncodeBuilder.SCREEN_DATA_TYPE.RGBA,
                false,
                16000,
                2
        );
        screenShareKit.onRGBA(new PreviewRGBACallback());
        screenShareKit.onError(new PreviewErrorCallback());
        screenShareKit.start();

        processingThread = new Thread(processingRunnable);
        processingRunnable.setActive(true);
        processingRunnable.setPaused(false);
        processingThread.start();
        started = true;

        return this;
    }

    public synchronized void pause() {
        processingRunnable.setPaused(true);
        cancelCurrentScan();
        frameChangeDetector.reset();
        cleanScreen();
    }

    public synchronized void resume() {
        if (!started) {
            return;
        }
        processingRunnable.setPaused(false);
    }

    public synchronized void retryOnce() {
        if (!started) {
            return;
        }
        processingRunnable.requestRetryOnce();
    }

    /**
     * Closes the camera and stops sending frames to the underlying frame detector.
     *
     * <p>This camera source may be restarted again by calling {@link #start()}
     *
     * <p>Call {@link #release()} instead to completely shut down this camera source and release the
     * resources of the underlying detector.
     */
    public synchronized void stop() {
        Log.d(TAG, "Stop");
        processingRunnable.setActive(false);
        processingRunnable.setPaused(false);
        if (processingThread != null) {
            try {
                // Wait for the thread to complete to ensure that we can't have multiple threads
                // executing at the same time (i.e., which would happen if we called start too
                // quickly after stop).
                processingThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Frame processing thread interrupted on release.");
            }
            processingThread = null;
        }

        if (screenShareKit != null) {
            screenShareKit.stop();
        }
        screenShareKit = null;
        ScreenDetectorSession.clearScreenFrameInfo();
        cancelCurrentScan();
        frameChangeDetector.reset();
        started = false;
    }

    // ==============================================================================================
    // Frame processing
    // ==============================================================================================

    /**
     * Called when the screen recorder has a new preview frame.
     */
    private class PreviewRGBACallback implements RGBACallBack {
        @Override
        public void onRGBA(byte[] rgba, int width, int height, int stride, int rotation, boolean rotationChanged) {
            FrameData data = new FrameData(rgba, width, height, stride, rotation);
            processingRunnable.setNextFrame(data);
        }
    }

    private class PreviewErrorCallback implements ErrorCallBack {
        @Override
        public void onError(@NonNull ErrorInfo errorInfo) {
            Log.e(TAG, "onError: " + errorInfo);
            release();
        }
    }

    public void setMachineLearningFrameProcessor(VisionImageProcessor processor) {
        synchronized (processorLock) {
            cleanScreen();
            if (frameProcessor != null) {
                frameProcessor.stop();
            }
            frameProcessor = processor;
        }
    }

    public void finishCurrentScan() {
        finishCurrentScan(null);
    }

    public void finishCurrentScan(Runnable publishResults) {
        int generation = activeScanGeneration.getAndSet(0);
        try {
            if (generation != 0 && publishResults != null) {
                publishResults.run();
            }
        } finally {
            if (generation != 0) {
                ScreenDetectorSession.finishScreenScan(generation);
                if (screenChangeDetectionEnabled) {
                    frameChangeDetector.onScanFinished();
                }
            }
            processorCallDispatched.set(false);
            isProcessingFrame.set(false);
        }
    }

    private void cancelCurrentScan() {
        synchronized (processorLock) {
            activeScanGeneration.set(0);
            ScreenDetectorSession.cancelScreenScan();
            if (!processorCallDispatched.get()) {
                isProcessingFrame.set(false);
            }
        }
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera. This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     * <p>
     * While detection is running on a frame, new frames may be received from the camera. As these
     * frames come in, the most recent frame is held onto as pending. As soon as detection and its
     * associated processing is done for the previous frame, detection on the mostly recently received
     * frame will immediately start on the same thread.
     */
    private class FrameProcessingRunnable implements Runnable {

        private final long period = PreferenceUtils.getScreenSearchIntervalMs(activity);

        private Timer timer;
        private TimerTask timerTask;

        // This lock guards all of the member variables below.
        private final Object lock = new Object();
        private boolean active = true;
        private boolean paused = false;
        private boolean retryOnceRequested = false;

        // These pending variables hold the state associated with the new frame awaiting processing.
        private FrameData pendingFrameData;

        FrameProcessingRunnable() {
        }

        private void startTimer() {
            timerTask = new TimerTask() {
                @Override
                public void run() {
                    FrameData data;
                    boolean shouldRetryOnce;

                    synchronized (lock) {
                        while (active && (pendingFrameData == null)) {
                            try {
                                // Wait for the next frame to be received from the camera, since we
                                // don't have it yet.
                                lock.wait();
                            } catch (InterruptedException e) {
                                Log.d(TAG, "Frame processing loop terminated.", e);
                                return;
                            }
                        }

                        if (!active) {
                            // Exit the loop once this camera source is stopped or released.  We check
                            // this here, immediately after the wait() above, to handle the case where
                            // setActive(false) had been called, triggering the termination of this
                            // loop.
                            return;
                        }

                        shouldRetryOnce = paused && retryOnceRequested;
                        if (paused && !shouldRetryOnce) {
                            pendingFrameData = null;
                            return;
                        }

                        // Hold onto the frame data locally, so that we can use this for detection
                        // below.  We need to clear pendingFrameData to ensure that this buffer isn't
                        // recycled back to the camera before we are done using that data.
                        data = pendingFrameData;
                        pendingFrameData = null;
                        if (shouldRetryOnce) {
                            retryOnceRequested = false;
                        }
                    }

                    try {
                        VisionImageProcessor processor;
                        synchronized (processorLock) {
                            processor = frameProcessor;
                        }
                        if (processor == null || !isProcessingFrame.compareAndSet(false, true)) {
                            return;
                        }
                        if (screenChangeDetectionEnabled) {
                            ScreenFrameChangeDetector.Decision changeDecision =
                                    frameChangeDetector.evaluate(
                                            data.rgba,
                                            data.width,
                                            data.height,
                                            data.stride,
                                            data.rotation,
                                            SystemClock.elapsedRealtime(),
                                            shouldRetryOnce
                                    );
                            if (changeDecision != ScreenFrameChangeDetector.Decision.SCAN) {
                                isProcessingFrame.set(false);
                                return;
                            }
                        }
                        List<Rect> fallbackAnnotationBounds =
                                ScreenDetectorSession.getAnnotationBoundsSnapshot();
                        int scanGeneration = ScreenDetectorSession.beginScreenScan();
                        activeScanGeneration.set(scanGeneration);
                        processorCallDispatched.set(false);
                        boolean resultsHidden =
                                ScreenDetectorSession.awaitScreenResultsHidden(
                                        scanGeneration,
                                        SCREEN_RESULTS_HIDE_TIMEOUT_MS
                                );
                        if (resultsHidden) {
                            fallbackAnnotationBounds.clear();
                        }
                        synchronized (lock) {
                            pendingFrameData = null;
                            while (
                                    active
                                            && (!paused || shouldRetryOnce)
                                            && pendingFrameData == null
                            ) {
                                try {
                                    lock.wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    cancelCurrentScan();
                                    return;
                                }
                            }
                            if (!active || (paused && !shouldRetryOnce)) {
                                cancelCurrentScan();
                                return;
                            }
                            data = pendingFrameData;
                            pendingFrameData = null;
                        }
                        if (data.width > 0
                                && data.height > 0
                                && (data.width != latestWidth || data.height != latestHeight)) {
                            latestWidth = data.width;
                            latestHeight = data.height;
                        }
                        Bitmap bitmap = createBitmapFromRgba(data);
                        if (bitmap == null) {
                            finishCurrentScan();
                            return;
                        }
                        final Bitmap processedBitmap =
                                maskOverlayBoundsIfNeeded(
                                        bitmap,
                                        data.width,
                                        data.height,
                                        data.rotation,
                                        fallbackAnnotationBounds
                                );
                        graphicOverlay.setImageSourceInfo(
                                processedBitmap.getWidth(),
                                processedBitmap.getHeight(),
                                false
                        );
                        ScreenDetectorSession.publishScreenFrameInfo(
                                processedBitmap.getWidth(),
                                processedBitmap.getHeight()
                        );
                        synchronized (processorLock) {
                            if (
                                    frameProcessor != processor
                                        || activeScanGeneration.get() != scanGeneration
                            ) {
                                processedBitmap.recycle();
                                finishCurrentScan();
                                return;
                            }
                            processorCallDispatched.set(true);
                            processor.processBitmap(processedBitmap, graphicOverlay, () -> {
                                processedBitmap.recycle();
                            });
                        }
                    } catch (Exception t) {
                        finishCurrentScan();
                        Log.e(TAG, "Exception thrown from receiver.", t);
                    }
                }
            };
            timer = new Timer("ScreenSourceFrameTimer");
            timer.schedule(timerTask, 0, period);
        }

        /**
         * Marks the runnable as active/not active. Signals any blocked threads to continue.
         */
        void setActive(boolean active) {
            synchronized (lock) {
                this.active = active;
                if (!active) {
                    if (timerTask != null) {
                        timerTask.cancel();
                        timerTask = null;
                    }
                    if (timer != null) {
                        timer.cancel();
                        timer = null;
                    }
                    pendingFrameData = null;
                    lock.notifyAll();
                }
            }
        }

        void setPaused(boolean paused) {
            synchronized (lock) {
                this.paused = paused;
                retryOnceRequested = false;
                if (paused) {
                    pendingFrameData = null;
                }
                lock.notifyAll();
            }
        }

        void requestRetryOnce() {
            synchronized (lock) {
                if (paused) {
                    retryOnceRequested = true;
                    lock.notifyAll();
                }
            }
        }


        /**
         * Sets the frame data received from the camera. This adds the previous unused frame buffer (if
         * present) back to the camera, and keeps a pending reference to the frame data for future use.
         */
        @SuppressWarnings("ByteBufferBackingArray")
        void setNextFrame(FrameData data) {
            synchronized (lock) {

                pendingFrameData = data;

                // Notify the processor thread if it is waiting on the next frame (see below).
                lock.notifyAll();
            }
        }

        @SuppressWarnings("ByteBufferBackingArray")
        @Override
        public void run() {
            synchronized (lock) {
                pendingFrameData = null;
            }
            startTimer();
        }
    }

    /**
     * Cleans up graphicOverlay and child classes can do their cleanups as well .
     */
    private void cleanScreen() {
        graphicOverlay.clear();
    }

    private Point getNativeCaptureSize() {
        Point size = new Point();
        try {
            activity.getWindowManager().getDefaultDisplay().getRealSize(size);
        } catch (Exception e) {
            Log.w(TAG, "Unable to get real display size, falling back to display metrics.", e);
        }
        if (size.x <= 0 || size.y <= 0) {
            size.x = activity.getResources().getDisplayMetrics().widthPixels;
            size.y = activity.getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private static class FrameData {
        byte[] rgba;
        int width;
        int height;
        int stride;
        int rotation;

        public FrameData(@NonNull byte[] rgba, int width, int height, int stride, int rotation) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.rotation = rotation;
        }
    }

    private static final long SCREEN_RESULTS_HIDE_TIMEOUT_MS = 180L;

    private Bitmap createBitmapFromRgba(FrameData data) {
        if (data.rgba == null || data.width <= 0 || data.height <= 0 || data.stride <= 0) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(data.stride, data.height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data.rgba));

        Bitmap croppedBitmap =
                data.stride == data.width
                        ? bitmap
                        : Bitmap.createBitmap(bitmap, 0, 0, data.width, data.height);
        if (croppedBitmap != bitmap) {
            bitmap.recycle();
        }

        if (data.rotation == 0) {
            return croppedBitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(data.rotation);
        Bitmap rotatedBitmap =
                Bitmap.createBitmap(
                        croppedBitmap,
                        0,
                        0,
                        croppedBitmap.getWidth(),
                        croppedBitmap.getHeight(),
                        matrix,
                        true
                );
        if (rotatedBitmap != croppedBitmap) {
            croppedBitmap.recycle();
        }
        return rotatedBitmap;
    }

    private Bitmap maskOverlayBoundsIfNeeded(
            Bitmap bitmap,
            int sourceWidth,
            int sourceHeight,
            int rotation,
            List<Rect> fallbackAnnotationBounds
    ) {
        Rect overlayBounds = ScreenDetectorSession.getOverlayBoundsSnapshot();
        List<Rect> annotationBounds = ScreenDetectorSession.getAnnotationBoundsSnapshot();
        List<Rect> maskSourceBounds = new ArrayList<>();
        if (overlayBounds != null && !overlayBounds.isEmpty()) {
            maskSourceBounds.add(overlayBounds);
        }
        maskSourceBounds.addAll(annotationBounds);
        maskSourceBounds.addAll(fallbackAnnotationBounds);
        if (maskSourceBounds.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) {
            return bitmap;
        }

        int displayWidth = Math.abs(rotation) % 180 == 0 ? sourceWidth : sourceHeight;
        int displayHeight = Math.abs(rotation) % 180 == 0 ? sourceHeight : sourceWidth;
        if (displayWidth <= 0 || displayHeight <= 0) {
            return bitmap;
        }

        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        float scaleX = (float) bitmapWidth / displayWidth;
        float scaleY = (float) bitmapHeight / displayHeight;
        Bitmap mutableBitmap = bitmap;
        Canvas canvas = null;
        Paint paint = null;
        for (Rect sourceBounds : maskSourceBounds) {
            Rect maskBounds =
                    new Rect(
                            Math.max(0, Math.round((sourceBounds.left - overlayMaskPaddingPx) * scaleX)),
                            Math.max(0, Math.round((sourceBounds.top - overlayMaskPaddingPx) * scaleY)),
                            Math.min(bitmapWidth, Math.round((sourceBounds.right + overlayMaskPaddingPx) * scaleX)),
                            Math.min(bitmapHeight, Math.round((sourceBounds.bottom + overlayMaskPaddingPx) * scaleY))
                    );
            if (maskBounds.isEmpty()) {
                continue;
            }
            if (!mutableBitmap.isMutable()) {
                mutableBitmap = mutableBitmap.copy(Bitmap.Config.ARGB_8888, true);
                bitmap.recycle();
            }
            if (canvas == null) {
                canvas = new Canvas(mutableBitmap);
                paint = new Paint();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
            }
            canvas.drawRect(maskBounds, paint);
        }
        return mutableBitmap;
    }
}
