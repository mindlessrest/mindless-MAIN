package mindless.utility.media;

/**
 * A port of cavacore, the processing engine behind CAVA (github.com/karlstav/cava, MIT).
 *
 * <p>Raw FFT output is a poor thing to draw. The bins are linear where hearing is logarithmic, so
 * nearly all the visible movement crowds into the first few bars; the magnitudes are enormous and
 * vary hugely between tracks; and frame to frame the values jitter far too fast to read. cavacore
 * is the accumulated answer to all three, and reproducing it is a great deal more sensible than
 * inventing a worse one:
 *
 * <ul>
 *   <li>bars distributed logarithmically between a low and high cut-off, so an octave occupies
 *       the same width wherever it falls;
 *   <li>a second, longer FFT used below 100Hz, because bass needs frequency resolution that a
 *       window short enough to stay responsive at the top end cannot give;
 *   <li>a hard-coded equaliser normalising the raw magnitudes into roughly 0..1;
 *   <li>two smoothing filters -- a weighted integral, and a gravity fall for values that drop --
 *       so bars settle rather than flicker;
 *   <li>automatic sensitivity, continuously rescaling so quiet and loud tracks both fill the
 *       display.
 * </ul>
 *
 * <p>The one part not carried across is FFTW. cavacore wraps it, but FFTW is GPL and the transform
 * sizes here are small and fixed, so {@link #transform} is a plain radix-2 FFT instead. It costs
 * around a megaflop per call at these sizes, runs sixty times a second on a background thread, and
 * has no bearing on the render thread.
 *
 * <p>Run in mono. The display is one row of bars, so a second channel would be work whose result
 * is discarded.
 */
final class CavaCore {
    static final int SCALING_LINEAR = 0;
    static final int SCALING_DECIBEL = 1;

    private static final double MAX_DECIBELS = 70.0;

    private final int bars;
    private final int rate;
    private final int scalingMode;
    private final boolean autosens;
    private final double noiseReduction;

    /** Long window, used below the bass cut-off where frequency resolution matters most. */
    private final int bassBufferSize;
    /** Short window for mid and treble, where responsiveness matters more than resolution. */
    private final int bufferSize;
    private final int inputBufferSize;

    private final double[] inputBuffer;
    private final double[] bassWindow;
    private final double[] window;

    private final double[] bassReal;
    private final double[] bassImag;
    private final double[] midReal;
    private final double[] midImag;

    private final int[] lowerCutOff;
    private final int[] upperCutOff;
    private final double[] eq;
    private final float[] cutOffFrequency;

    private final double[] fall;
    private final double[] mem;
    private final double[] peak;
    private final double[] previousOut;

    private int bassCutOffBar;
    private double sensitivity = 1.0;
    private boolean sensitivityInitialising = true;
    private double framerate = 75.0;
    private int frameSkip = 1;

    /** Bit-reversal and twiddle tables, one set per transform length. */
    private final FftTables bassTables;
    private final FftTables midTables;

    CavaCore(int bars, int rate, boolean autosens, double noiseReduction, int lowCutOff,
             int highCutOff, int scalingMode) {
        if (rate < 1 || rate > 384000) {
            throw new IllegalArgumentException("illegal sample rate: " + rate);
        }

        int fftBufferSize = 512;
        if (rate > 8125 && rate <= 16250) fftBufferSize *= 2;
        else if (rate > 16250 && rate <= 32500) fftBufferSize *= 4;
        else if (rate > 32500 && rate <= 75000) fftBufferSize *= 8;
        else if (rate > 75000 && rate <= 150000) fftBufferSize *= 16;
        else if (rate > 150000 && rate <= 300000) fftBufferSize *= 32;
        else if (rate > 300000) fftBufferSize *= 64;

        if (bars < 1) {
            throw new IllegalArgumentException("illegal bar count: " + bars);
        }
        if (bars > fftBufferSize / 2 + 1) {
            throw new IllegalArgumentException("too many bars for this sample rate: " + bars);
        }
        if (lowCutOff < 1 || highCutOff < 1 || lowCutOff >= highCutOff) {
            throw new IllegalArgumentException("illegal cut-off pair");
        }
        if (highCutOff > rate / 2) {
            throw new IllegalArgumentException("high cut-off exceeds Nyquist");
        }

        this.bars = bars;
        this.rate = rate;
        this.autosens = autosens;
        this.noiseReduction = noiseReduction;
        this.scalingMode = scalingMode;

        this.bassBufferSize = fftBufferSize * 2;
        this.bufferSize = fftBufferSize;
        this.inputBufferSize = bassBufferSize;

        this.inputBuffer = new double[inputBufferSize];
        this.bassWindow = new double[bassBufferSize];
        this.window = new double[bufferSize];

        this.bassReal = new double[bassBufferSize];
        this.bassImag = new double[bassBufferSize];
        this.midReal = new double[bufferSize];
        this.midImag = new double[bufferSize];

        this.lowerCutOff = new int[bars + 1];
        this.upperCutOff = new int[bars + 1];
        this.eq = new double[bars + 1];
        this.cutOffFrequency = new float[bars + 1];

        this.fall = new double[bars];
        this.mem = new double[bars];
        this.peak = new double[bars];
        this.previousOut = new double[bars];

        this.bassTables = new FftTables(bassBufferSize);
        this.midTables = new FftTables(bufferSize);

        // Hann window, precomputed.
        for (int i = 0; i < bassBufferSize; i++) {
            bassWindow[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (bassBufferSize - 1)));
        }
        for (int i = 0; i < bufferSize; i++) {
            window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (bufferSize - 1)));
        }

        computeCutOffs(lowCutOff, highCutOff);
        computeEqualiser();
    }

    int getBars() {
        return bars;
    }

    /**
     * Works out which FFT bins each bar covers.
     *
     * <p>Bars are spread logarithmically across the band, then snapped to bin boundaries. Two
     * corrections matter: bars falling below the bass cut-off read from the long transform, and
     * where the logarithm bunches several bars into the same bin at the bottom they are pushed
     * apart one bin at a time so no bar ends up empty.
     */
    private void computeCutOffs(int lowCutOff, int highCutOff) {
        final int bassCutOff = 100;
        double frequencyConstant = Math.log10((double) lowCutOff / (double) highCutOff)
                / (1.0 / (bars + 1.0) - 1.0);

        float[] relative = new float[bars + 1];
        bassCutOffBar = 0;
        boolean firstBar = true;
        float minBandwidth = (float) rate / bassBufferSize;

        for (int n = 0; n < bars + 1; n++) {
            double coefficient = -frequencyConstant + ((n + 1.0) / (bars + 1.0)) * frequencyConstant;
            cutOffFrequency[n] = (float) (highCutOff * Math.pow(10, coefficient));

            if (n > 0 && cutOffFrequency[n - 1] >= cutOffFrequency[n]) {
                cutOffFrequency[n] = cutOffFrequency[n - 1] + minBandwidth;
            }

            relative[n] = cutOffFrequency[n] / (rate / 2.0F);

            if (cutOffFrequency[n] < bassCutOff) {
                lowerCutOff[n] = (int) (relative[n] * (bassBufferSize / 2));
                bassCutOffBar++;
                if (bassCutOffBar > 1) firstBar = false;
                if (lowerCutOff[n] > bassBufferSize / 2) lowerCutOff[n] = bassBufferSize / 2;
            }
            else {
                lowerCutOff[n] = (int) Math.ceil(relative[n] * (bufferSize / 2.0F));
                if (n == bassCutOffBar) {
                    firstBar = true;
                    if (n > 0) {
                        upperCutOff[n - 1] = (int) (relative[n] * (bassBufferSize / 2)) - 1;
                    }
                }
                else {
                    firstBar = false;
                }
                if (lowerCutOff[n] > bufferSize / 2) lowerCutOff[n] = bufferSize / 2;
            }

            if (n > 0) {
                if (!firstBar) {
                    upperCutOff[n - 1] = lowerCutOff[n] - 1;

                    // The logarithm clumps several bars into one bin down in the bass. Push the
                    // spectrum up a bin at a time where there is room, so each bar still has one.
                    if (lowerCutOff[n] <= lowerCutOff[n - 1]) {
                        boolean roomForMore = n < bassCutOffBar
                                ? lowerCutOff[n - 1] + 1 < bassBufferSize / 2 + 1
                                : lowerCutOff[n - 1] + 1 < bufferSize / 2 + 1;
                        if (roomForMore) {
                            lowerCutOff[n] = lowerCutOff[n - 1] + 1;
                            upperCutOff[n - 1] = lowerCutOff[n] - 1;
                        }
                    }
                }
                else if (upperCutOff[n - 1] < lowerCutOff[n - 1]) {
                    upperCutOff[n - 1] = lowerCutOff[n - 1] + 1;
                }
            }

            relative[n] = n < bassCutOffBar
                    ? (float) lowerCutOff[n] / (bassBufferSize / 2.0F)
                    : (float) lowerCutOff[n] / (bufferSize / 2.0F);
            cutOffFrequency[n] = relative[n] * (rate / 2.0F);
        }
    }

    /** cavacore's hard-coded equaliser: divides the very large raw FFT magnitudes into 0..1. */
    private void computeEqualiser() {
        for (int n = 0; n < bars; n++) {
            eq[n] = 1.0 / Math.pow(2, 28);
            eq[n] *= Math.pow(cutOffFrequency[n + 1], 0.85);
            eq[n] /= n < bassCutOffBar
                    ? (Math.log(bassBufferSize) / Math.log(2))
                    : (Math.log(bufferSize) / Math.log(2));
            eq[n] /= upperCutOff[n] - lowerCutOff[n] + 1;
        }
    }

    /**
     * Runs one frame of visualisation.
     *
     * @param input       new mono samples, oldest first
     * @param newSamples  how many of them are valid
     * @param output      receives one value per bar, roughly 0..1
     */
    void execute(double[] input, int newSamples, double[] output) {
        if (newSamples > inputBufferSize) newSamples = inputBufferSize;

        boolean silence = true;
        if (newSamples > 0) {
            // Approximate the real frame rate, so the smoothing and sensitivity filters stay
            // consistent whether they are being driven at 30 or 144 frames a second.
            framerate -= framerate / 64.0;
            framerate += (double) (rate * frameSkip) / newSamples / 64.0;
            frameSkip = 1;

            System.arraycopy(inputBuffer, 0, inputBuffer, newSamples, inputBufferSize - newSamples);
            for (int n = 0; n < newSamples; n++) {
                double sample = input[n];
                inputBuffer[newSamples - n - 1] = sample;
                if (sample != 0.0) silence = false;
            }
        }
        else {
            frameSkip++;
        }

        for (int n = 0; n < bassBufferSize; n++) {
            bassReal[n] = bassWindow[n] * inputBuffer[n];
            bassImag[n] = 0.0;
        }
        for (int n = 0; n < bufferSize; n++) {
            midReal[n] = window[n] * inputBuffer[n];
            midImag[n] = 0.0;
        }

        transform(bassReal, bassImag, bassTables);
        transform(midReal, midImag, midTables);

        for (int n = 0; n < bars; n++) {
            double magnitude = 0.0;
            boolean bass = n < bassCutOffBar;
            double[] real = bass ? bassReal : midReal;
            double[] imag = bass ? bassImag : midImag;
            int limit = (bass ? bassBufferSize : bufferSize) / 2;

            for (int i = lowerCutOff[n]; i <= upperCutOff[n] && i <= limit; i++) {
                magnitude += Math.hypot(real[i], imag[i]);
            }

            if (scalingMode == SCALING_DECIBEL) {
                magnitude = 20 * Math.log10(magnitude) / MAX_DECIBELS;
                if (Double.isNaN(magnitude) || Double.isInfinite(magnitude)) magnitude = 0.0;
            }
            else {
                magnitude *= eq[n];
            }
            output[n] = magnitude;
        }

        if (autosens) {
            for (int n = 0; n < bars; n++) output[n] *= sensitivity;
        }

        boolean overshoot = false;
        double framerateMod = 66.0 / framerate;
        double gravityMod = Math.pow(framerateMod, 2.5) * 2 / noiseReduction;
        double integralMod = Math.pow(framerateMod, 0.1);

        for (int n = 0; n < bars; n++) {
            // Falloff: a bar that drops does not snap down, it falls under gravity from where it
            // peaked. This is most of what makes the movement read as physical rather than noisy.
            if (output[n] < previousOut[n] && noiseReduction > 0.1) {
                output[n] = peak[n] * (1.0 - (fall[n] * fall[n] * gravityMod));
                if (output[n] < 0.0) output[n] = 0.0;
                fall[n] += 0.028;
            }
            else {
                peak[n] = output[n];
                fall[n] = 0.0;
            }
            previousOut[n] = output[n];

            // Integral: a weighted average with everything before it.
            output[n] = mem[n] * noiseReduction / integralMod + output[n];
            mem[n] = output[n];

            if (autosens && output[n] > 1.0) {
                overshoot = true;
                output[n] = 1.0;
            }
        }

        if (autosens) {
            if (overshoot) {
                sensitivity *= 1 - (0.02 * framerateMod);
                sensitivityInitialising = false;
            }
            else if (!silence) {
                sensitivity *= 1 + (0.001 * framerateMod);
                if (sensitivityInitialising) sensitivity *= 1 + (0.1 * framerateMod);
            }
        }
    }

    // --------------------------------------------------------------------------------- transform

    /** Bit-reversal permutation and twiddle factors for one transform length. */
    private static final class FftTables {
        final int size;
        final int[] reversed;
        final double[] cos;
        final double[] sin;

        FftTables(int size) {
            this.size = size;
            this.reversed = new int[size];
            this.cos = new double[size / 2];
            this.sin = new double[size / 2];

            int bits = Integer.numberOfTrailingZeros(size);
            for (int i = 0; i < size; i++) {
                reversed[i] = Integer.reverse(i) >>> (32 - bits);
            }
            for (int i = 0; i < size / 2; i++) {
                cos[i] = Math.cos(-2 * Math.PI * i / size);
                sin[i] = Math.sin(-2 * Math.PI * i / size);
            }
        }
    }

    /**
     * In-place iterative radix-2 FFT.
     *
     * <p>The input is real, so half the work here is multiplying by a zero imaginary part and half
     * the output is a mirror of the other half. A real-input transform would avoid both. It is not
     * worth it: at 8192 and 4096 points, sixty times a second, off the render thread, this is
     * already far below the noise floor of anything else the client does per frame, and a
     * hand-rolled real transform is a much easier thing to get subtly wrong.
     */
    private static void transform(double[] real, double[] imag, FftTables tables) {
        final int n = tables.size;

        for (int i = 0; i < n; i++) {
            int j = tables.reversed[i];
            if (j > i) {
                double swapReal = real[i];
                real[i] = real[j];
                real[j] = swapReal;
                double swapImag = imag[i];
                imag[i] = imag[j];
                imag[j] = swapImag;
            }
        }

        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int partner = j + half;
                    double twiddleReal = tables.cos[k];
                    double twiddleImag = tables.sin[k];
                    double productReal = real[partner] * twiddleReal - imag[partner] * twiddleImag;
                    double productImag = real[partner] * twiddleImag + imag[partner] * twiddleReal;
                    real[partner] = real[j] - productReal;
                    imag[partner] = imag[j] - productImag;
                    real[j] += productReal;
                    imag[j] += productImag;
                }
            }
        }
    }
}
