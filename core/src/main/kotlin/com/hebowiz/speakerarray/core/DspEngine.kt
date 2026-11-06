package com.hebowiz.speakerarray.core

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

private const val FFT_SIZE = 2048
private const val HISTORY = FFT_SIZE-BLOCK
private const val FADE_SAMPLES = 1440

data class Spectrum(val re: DoubleArray, val im: DoubleArray)
data class PreparedProcessing(val parameters: Processing, val left: Spectrum, val right: Spectrum, val generation: Long) {
    companion object {
        fun create(p: Processing, generation: Long = 0): PreparedProcessing {
            p.validate()
            fun make(ch: Channel, right: Boolean): Spectrum {
                val fir = if (ch.enabled) FirDesigner.design(ch.fir, p.length) else FirDesigner.flat(p.length)
                val delay = FractionalDelay.kernel(max(0.0, if (right) p.delayUs else -p.delayUs)*SAMPLE_RATE/1e6)
                val re = DoubleArray(FFT_SIZE); val im = DoubleArray(FFT_SIZE)
                val gain = if (ch.muted) 0.0 else dbGain(ch.gainDb)*(if (ch.inverted) -1 else 1)
                for (i in fir.indices) for (j in delay.indices) re[i+j] += fir[i]*delay[j]*gain
                Fft.transform(re, im)
                return Spectrum(re, im)
            }
            return PreparedProcessing(p, make(p.left, false), make(p.right, true), generation)
        }
    }
}

/** Fixed block overlap-save. Old/new kernels see exactly the same input history. */
class DspEngine(initial: PreparedProcessing) {
    private val pending = AtomicReference<PreparedProcessing?>(null)
    private var current = initial
    private var next: PreparedProcessing? = null
    private var fade = 0
    private val historyL = DoubleArray(FFT_SIZE)
    private val historyR = DoubleArray(FFT_SIZE)
    private val inputRe = DoubleArray(FFT_SIZE)
    private val inputIm = DoubleArray(FFT_SIZE)
    private val workRe = DoubleArray(FFT_SIZE)
    private val workIm = DoubleArray(FFT_SIZE)
    private val oldL = DoubleArray(BLOCK)
    private val oldR = DoubleArray(BLOCK)
    private val newL = DoubleArray(BLOCK)
    private val newR = DoubleArray(BLOCK)
    @Volatile var appliedGeneration = initial.generation; private set
    @Volatile var switching = false; private set
    var peakLeft = 0.0; private set
    var peakRight = 0.0; private set
    var clips = 0L; private set
    private var master = 0.0
    fun submit(value: PreparedProcessing) { pending.set(value) }

    private fun convolve(history: DoubleArray, input: DoubleArray, old: Spectrum, fresh: Spectrum?, out: DoubleArray, alternate: DoubleArray) {
        System.arraycopy(history, BLOCK, history, 0, HISTORY)
        System.arraycopy(input, 0, history, HISTORY, BLOCK)
        System.arraycopy(history, 0, inputRe, 0, FFT_SIZE); inputIm.fill(0.0)
        Fft.transform(inputRe, inputIm)
        fun run(spectrum: Spectrum, dest: DoubleArray) {
            for (i in 0 until FFT_SIZE) {
                workRe[i] = inputRe[i]*spectrum.re[i]-inputIm[i]*spectrum.im[i]
                workIm[i] = inputRe[i]*spectrum.im[i]+inputIm[i]*spectrum.re[i]
            }
            Fft.transform(workRe, workIm, true)
            System.arraycopy(workRe, HISTORY, dest, 0, BLOCK)
        }
        run(old, out)
        if (fresh != null) run(fresh, alternate)
    }
    fun process(left: DoubleArray, right: DoubleArray, output: FloatArray, masterDb: Double, swapped: Boolean, envelope: DoubleArray? = null) {
        require(left.size == BLOCK && right.size == BLOCK && output.size == 2*BLOCK)
        if (next == null) pending.getAndSet(null)?.let { next = it; fade = 0 }
        val target = next
        switching = target != null || pending.get() != null
        convolve(historyL, left, current.left, target?.left, oldL, newL)
        convolve(historyR, right, current.right, target?.right, oldR, newR)
        val targetMaster = dbGain(masterDb)
        peakLeft = 0.0; peakRight = 0.0
        for (i in 0 until BLOCK) {
            val mix = if (target != null) min(1.0, (fade+i+1).toDouble()/FADE_SAMPLES) else 0.0
            master += (targetMaster-master)/240.0
            val e = envelope?.get(i) ?: 1.0
            var l = (oldL[i]*(1-mix)+(if (target != null) newL[i]*mix else 0.0))*master*e
            var r = (oldR[i]*(1-mix)+(if (target != null) newR[i]*mix else 0.0))*master*e
            if (!l.isFinite()) { clips++; l = 0.0 }; if (!r.isFinite()) { clips++; r = 0.0 }
            peakLeft = max(peakLeft, abs(l)); peakRight = max(peakRight, abs(r))
            if (abs(l) > 1) clips++; if (abs(r) > 1) clips++
            output[2*i+(if (swapped) 1 else 0)] = l.coerceIn(-1.0, 1.0).toFloat()
            output[2*i+(if (swapped) 0 else 1)] = r.coerceIn(-1.0, 1.0).toFloat()
        }
        if (target != null) {
            fade += BLOCK
            if (fade >= FADE_SAMPLES) { current = target; next = null; appliedGeneration = current.generation }
        }
    }
}

/** Handles arbitrary partial writes without losing an interleaved sample. */
object StereoWriter {
    fun writeAll(samples: FloatArray, write: (FloatArray, Int, Int) -> Int) {
        var offset = 0; var zeroWrites = 0
        while (offset < samples.size) {
            val n = write(samples, offset, samples.size-offset)
            check(n >= 0 && n <= samples.size-offset) { "AudioTrack write error: $n" }
            if (n == 0) { check(++zeroWrites <= 8) { "AudioTrack write stalled" }; Thread.yield() }
            else { zeroWrites = 0; offset += n }
        }
    }
}
