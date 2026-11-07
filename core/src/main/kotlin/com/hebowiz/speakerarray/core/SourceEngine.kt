package com.hebowiz.speakerarray.core

import kotlin.math.*
import java.util.Random

class SourceEngine(val settings: SourceSettings, private val wav: WavSource? = null) {
    private val random = Random(0x53504541)
    private var phase = 0.0
    private var frequency = settings.frequency
    private var relative = 0.0
    private var elapsed = 0L
    private val pink = DoubleArray(7)
    private val noiseHistory = DoubleArray(257)
    private var noiseIndex = 0
    private val band = if (settings.bandLimit) FirDesigner.design(FirSpec(FirMode.BPF, low=settings.low, high=settings.high), 257) else doubleArrayOf(1.0)
    private val wavBlock = DoubleArray(BLOCK)
    var finished = false; private set
    init { settings.validate() }
    fun render(left: DoubleArray, right: DoubleArray, targetHz: Double, targetPhaseDeg: Double, quadrature: Boolean = false) {
        if (settings.kind == SourceKind.WAV) { wav?.read(wavBlock); finished = wav?.finished ?: true }
        for (i in 0 until BLOCK) {
            frequency += (targetHz-frequency)/240.0
            var delta = targetPhaseDeg*PI/180-relative
            while (delta > PI) delta -= 2*PI
            while (delta < -PI) delta += 2*PI
            relative += delta/240
            val sample = when (settings.kind) {
                SourceKind.SINE -> sin(phase)
                SourceKind.WHITE, SourceKind.PINK -> {
                    val white = random.nextDouble()*2-1
                    val raw = if (settings.kind == SourceKind.WHITE) white*sqrt(3.0)*0.2 else pinkSample(white)
                    noiseHistory[noiseIndex] = raw
                    var sum = 0.0; var j = noiseIndex
                    for (h in band) { sum += h*noiseHistory[j]; if (--j < 0) j = noiseHistory.lastIndex }
                    noiseIndex = (noiseIndex+1)%noiseHistory.size
                    sum
                }
                SourceKind.SWEEP -> {
                    val duration = (settings.sweepSeconds*SAMPLE_RATE).toLong()
                    if (elapsed >= duration) { if (settings.loop) { elapsed = 0; phase = 0.0 } else finished = true }
                    val t = elapsed.toDouble()/duration
                    frequency = if (settings.logarithmic) settings.low*(settings.high/settings.low).pow(t) else settings.low+(settings.high-settings.low)*t
                    val env = min(1.0, min(elapsed.toDouble()/480, (duration-elapsed).toDouble()/480)).coerceAtLeast(0.0)
                    if (finished) 0.0 else sin(phase)*env
                }
                SourceKind.WAV -> wavBlock[i]
            }
            left[i] = sample
            right[i] = if (settings.kind == SourceKind.SINE) if(quadrature) cos(phase) else sin(phase+relative) else sample
            phase = (phase+2*PI*frequency/SAMPLE_RATE)%(2*PI)
            elapsed++
        }
    }
    private fun pinkSample(w: Double): Double {
        // Paul Kellet's pk3 pinking filter, with a fixed source-level scale.
        // Reference: https://www.firstpr.com.au/dsp/pink-noise/
        pink[0] = 0.99886*pink[0]+w*0.0555179
        pink[1] = 0.99332*pink[1]+w*0.0750759
        pink[2] = 0.969*pink[2]+w*0.1538520
        pink[3] = 0.8665*pink[3]+w*0.3104856
        pink[4] = 0.55*pink[4]+w*0.5329522
        pink[5] = -0.7616*pink[5]-w*0.0168980
        val x = (pink.sum()+w*0.5362)*0.11*0.2/0.199
        pink[6] = w*0.115926
        return x
    }
}
