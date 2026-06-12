package com.virin.visionquiz;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScreenFrameChangeDetectorTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    @Test
    public void firstFrameScans_thenNextFrameEstablishesBaseline() {
        ScreenFrameChangeDetector detector = new ScreenFrameChangeDetector();
        byte[] frame = frameWithChangedSamples(0);

        assertEquals(
                ScreenFrameChangeDetector.Decision.SCAN,
                detector.evaluate(frame, WIDTH, HEIGHT, WIDTH, 0, 0L, false)
        );
        detector.onScanFinished();
        assertEquals(
                ScreenFrameChangeDetector.Decision.BASELINE_ESTABLISHED,
                detector.evaluate(frame, WIDTH, HEIGHT, WIDTH, 0, 200L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.UNCHANGED,
                detector.evaluate(frame, WIDTH, HEIGHT, WIDTH, 0, 400L, false)
        );
    }

    @Test
    public void changesBelowThreePercentAreIgnored() {
        ScreenFrameChangeDetector detector = detectorWithBaseline();
        byte[] smallChange = frameWithChangedSamples(122);

        assertEquals(
                ScreenFrameChangeDetector.Decision.UNCHANGED,
                detector.evaluate(smallChange, WIDTH, HEIGHT, WIDTH, 0, 300L, false)
        );
    }

    @Test
    public void stablePageChangeScansAfterDelay() {
        ScreenFrameChangeDetector detector = detectorWithBaseline();
        byte[] changed = frameWithChangedSamples(124);

        assertEquals(
                ScreenFrameChangeDetector.Decision.WAITING_FOR_STABILITY,
                detector.evaluate(changed, WIDTH, HEIGHT, WIDTH, 0, 300L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.WAITING_FOR_STABILITY,
                detector.evaluate(changed, WIDTH, HEIGHT, WIDTH, 0, 419L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.SCAN,
                detector.evaluate(changed, WIDTH, HEIGHT, WIDTH, 0, 420L, false)
        );
    }

    @Test
    public void movingTransitionWaitsUntilCandidateStabilizes() {
        ScreenFrameChangeDetector detector = detectorWithBaseline();
        byte[] firstTransition = frameWithChangedSamples(600);
        byte[] secondTransition = frameWithChangedRange(600, 600);

        assertEquals(
                ScreenFrameChangeDetector.Decision.WAITING_FOR_STABILITY,
                detector.evaluate(firstTransition, WIDTH, HEIGHT, WIDTH, 0, 300L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.WAITING_FOR_STABILITY,
                detector.evaluate(secondTransition, WIDTH, HEIGHT, WIDTH, 0, 420L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.SCAN,
                detector.evaluate(secondTransition, WIDTH, HEIGHT, WIDTH, 0, 540L, false)
        );
    }

    @Test
    public void geometryChangeAndManualRetryScanImmediately() {
        ScreenFrameChangeDetector detector = detectorWithBaseline();
        byte[] unchanged = frameWithChangedSamples(0);

        assertEquals(
                ScreenFrameChangeDetector.Decision.SCAN,
                detector.evaluate(unchanged, WIDTH, HEIGHT, WIDTH, 90, 300L, false)
        );
        assertEquals(
                ScreenFrameChangeDetector.Decision.SCAN,
                detector.evaluate(unchanged, WIDTH, HEIGHT, WIDTH, 0, 400L, true)
        );
    }

    private static ScreenFrameChangeDetector detectorWithBaseline() {
        ScreenFrameChangeDetector detector = new ScreenFrameChangeDetector();
        byte[] baseline = frameWithChangedSamples(0);
        detector.evaluate(baseline, WIDTH, HEIGHT, WIDTH, 0, 0L, false);
        detector.onScanFinished();
        detector.evaluate(baseline, WIDTH, HEIGHT, WIDTH, 0, 200L, false);
        return detector;
    }

    private static byte[] frameWithChangedSamples(int count) {
        return frameWithChangedRange(0, count);
    }

    private static byte[] frameWithChangedRange(int start, int count) {
        byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        for (int index = start; index < Math.min(WIDTH * HEIGHT, start + count); index++) {
            int offset = index * 4;
            rgba[offset] = (byte) 255;
            rgba[offset + 1] = (byte) 255;
            rgba[offset + 2] = (byte) 255;
            rgba[offset + 3] = (byte) 255;
        }
        return rgba;
    }
}
