package com.virin.visionquiz;

/** Detects stable, page-level changes between captured RGBA screen frames. */
public final class ScreenFrameChangeDetector {

    static final int SAMPLE_SIZE = 64;
    static final int PIXEL_DIFFERENCE_THRESHOLD = 24;
    static final double PAGE_CHANGE_THRESHOLD = 0.03;
    static final double STABILITY_THRESHOLD = 0.015;
    static final long STABILITY_DELAY_MS = 120L;

    public enum Decision {
        BASELINE_ESTABLISHED,
        UNCHANGED,
        WAITING_FOR_STABILITY,
        SCAN
    }

    private FrameSignature baseline;
    private FrameSignature candidate;
    private long candidateSinceMs;
    private boolean establishBaselineAfterScan;

    public synchronized Decision evaluate(
            byte[] rgba,
            int width,
            int height,
            int stride,
            int rotation,
            long nowMs,
            boolean forceScan
    ) {
        FrameSignature current = FrameSignature.create(rgba, width, height, stride, rotation);
        if (forceScan) {
            candidate = null;
            return Decision.SCAN;
        }
        if (current == null) {
            return Decision.UNCHANGED;
        }
        if (baseline == null) {
            if (establishBaselineAfterScan) {
                baseline = current;
                establishBaselineAfterScan = false;
                candidate = null;
                return Decision.BASELINE_ESTABLISHED;
            }
            return Decision.SCAN;
        }
        if (!baseline.hasSameGeometry(current)) {
            candidate = null;
            return Decision.SCAN;
        }
        if (baseline.differenceRatio(current) < PAGE_CHANGE_THRESHOLD) {
            candidate = null;
            return Decision.UNCHANGED;
        }
        if (candidate == null || !candidate.hasSameGeometry(current)) {
            candidate = current;
            candidateSinceMs = nowMs;
            return Decision.WAITING_FOR_STABILITY;
        }
        if (nowMs - candidateSinceMs < STABILITY_DELAY_MS) {
            return Decision.WAITING_FOR_STABILITY;
        }
        if (candidate.differenceRatio(current) <= STABILITY_THRESHOLD) {
            candidate = null;
            return Decision.SCAN;
        }
        candidate = current;
        candidateSinceMs = nowMs;
        return Decision.WAITING_FOR_STABILITY;
    }

    public synchronized void onScanFinished() {
        baseline = null;
        candidate = null;
        establishBaselineAfterScan = true;
    }

    public synchronized void reset() {
        baseline = null;
        candidate = null;
        candidateSinceMs = 0L;
        establishBaselineAfterScan = false;
    }

    static final class FrameSignature {
        private final byte[] luminance;
        private final int width;
        private final int height;
        private final int rotation;

        private FrameSignature(byte[] luminance, int width, int height, int rotation) {
            this.luminance = luminance;
            this.width = width;
            this.height = height;
            this.rotation = normalizeRotation(rotation);
        }

        static FrameSignature create(
                byte[] rgba,
                int width,
                int height,
                int stride,
                int rotation
        ) {
            if (rgba == null || width <= 0 || height <= 0 || stride < width) {
                return null;
            }
            long requiredBytes = (long) stride * height * 4L;
            if (requiredBytes > rgba.length) {
                return null;
            }
            byte[] samples = new byte[SAMPLE_SIZE * SAMPLE_SIZE];
            for (int sampleY = 0; sampleY < SAMPLE_SIZE; sampleY++) {
                int sourceY = Math.min(
                        height - 1,
                        ((sampleY * 2 + 1) * height) / (SAMPLE_SIZE * 2)
                );
                for (int sampleX = 0; sampleX < SAMPLE_SIZE; sampleX++) {
                    int sourceX = Math.min(
                            width - 1,
                            ((sampleX * 2 + 1) * width) / (SAMPLE_SIZE * 2)
                    );
                    int offset = (sourceY * stride + sourceX) * 4;
                    int red = rgba[offset] & 0xff;
                    int green = rgba[offset + 1] & 0xff;
                    int blue = rgba[offset + 2] & 0xff;
                    samples[sampleY * SAMPLE_SIZE + sampleX] =
                            (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
                }
            }
            return new FrameSignature(samples, width, height, rotation);
        }

        boolean hasSameGeometry(FrameSignature other) {
            return other != null
                    && width == other.width
                    && height == other.height
                    && rotation == other.rotation;
        }

        double differenceRatio(FrameSignature other) {
            if (!hasSameGeometry(other)) {
                return 1.0;
            }
            int changed = 0;
            for (int index = 0; index < luminance.length; index++) {
                int first = luminance[index] & 0xff;
                int second = other.luminance[index] & 0xff;
                if (Math.abs(first - second) >= PIXEL_DIFFERENCE_THRESHOLD) {
                    changed++;
                }
            }
            return (double) changed / luminance.length;
        }

        private static int normalizeRotation(int rotation) {
            int normalized = rotation % 360;
            return normalized < 0 ? normalized + 360 : normalized;
        }
    }
}
