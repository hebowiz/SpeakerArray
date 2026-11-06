package com.hebowiz.speakerarray.core

import kotlin.math.*

const val SAMPLE_RATE = 48000
const val BLOCK = 256
val EQ_HZ = listOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 12000.0, 16000.0)
enum class SourceKind { SINE, WHITE, PINK, SWEEP, WAV }
enum class FirMode { FLAT, EQ, LPF, HPF, BPF, COEFFICIENTS }
enum class MonoMode { AVERAGE, LEFT, RIGHT }
data class FirSpec(val mode: FirMode = FirMode.FLAT, val eq: List<Double> = List(10) { 0.0 },
    val low: Double = 300.0, val high: Double = 8000.0, val coefficients: List<Double> = listOf(1.0))
data class Channel(val gainDb: Double = 0.0, val muted: Boolean = false, val inverted: Boolean = false,
    val enabled: Boolean = true, val fir: FirSpec = FirSpec())
data class Processing(val length: Int = 513, val left: Channel = Channel(), val right: Channel = Channel(),
    val delayUs: Double = 0.0, val phaseDeg: Double = 0.0)
data class SourceSettings(val kind: SourceKind = SourceKind.SINE, val frequency: Double = 1143.0,
    val bandLimit: Boolean = true, val low: Double = 300.0, val high: Double = 8000.0,
    val sweepSeconds: Double = 5.0, val logarithmic: Boolean = true, val loop: Boolean = true,
    val wavUri: String = "", val mono: MonoMode = MonoMode.AVERAGE, val loopFade: Boolean = true)
data class Session(val processing: Processing = Processing(), val source: SourceSettings = SourceSettings(),
    val masterDb: Double = -24.0, val swapped: Boolean = false, val reverseLandscape: Boolean = false,
    val orientationChosen: Boolean = false, val spacingMm: Double = 150.0, val soundSpeed: Double = 343.0,
    val evaluationHz: Double = 1143.0, val a: Processing? = null, val b: Processing? = null,
    val autoFir: AutoFirSettings = AutoFirSettings(), val calibration: CalibrationProfile? = null) {
    val previewFrequencyHz: Double
        get() = if (source.kind == SourceKind.SINE) source.frequency else evaluationHz
}

fun finiteRange(value: Double, low: Double, high: Double, label: String) {
    require(value.isFinite() && value in low..high) { "$label は $low ～ $high の有限値で指定してください" }
}
fun Processing.validate() {
    require(length in listOf(257, 513, 1025)) { "FIR長は257・513・1025です" }
    finiteRange(delayUs, -10000.0, 10000.0, "相対遅延 µs")
    finiteRange(phaseDeg, -180.0, 180.0, "相対位相 °")
    listOf(left, right).forEach { ch ->
        finiteRange(ch.gainDb, -60.0, 12.0, "ゲイン dB")
        FirDesigner.validate(ch.fir, length)
    }
}
fun Session.validate() {
    autoFir.validate()
    processing.validate()
    finiteRange(masterDb, -60.0, 0.0, "共通出力 dB")
    finiteRange(spacingMm, 0.0, 3000.0, "音源間隔 mm")
    finiteRange(soundSpeed, 250.0, 400.0, "音速 m/s")
    finiteRange(evaluationHz, 20.0, 20000.0, "評価周波数 Hz")
    source.validate()
    listOfNotNull(a, b).forEach { it.validate(); require(it.length == processing.length) { "A/BのFIR長が一致しません" } }
}
fun SourceSettings.validate() {
    finiteRange(frequency, 20.0, 20000.0, "周波数 Hz")
    finiteRange(low, 1.0, 23999.0, "下限 Hz")
    finiteRange(high, 1.0, 23999.0, "上限 Hz")
    require(low < high) { "下限は上限より小さくしてください" }
    finiteRange(sweepSeconds, 0.1, 600.0, "スイープ時間 s")
}
fun dbGain(db: Double) = 10.0.pow(db / 20.0)
fun levelDb(value: Double) = 20 * log10(max(abs(value), 1e-6))
fun wrapPhase(degrees: Double) = (((degrees + 180) % 360) + 360) % 360 - 180

data class Complex(val re: Double, val im: Double) {
    operator fun plus(b: Complex) = Complex(re + b.re, im + b.im)
    operator fun times(b: Complex) = Complex(re*b.re - im*b.im, re*b.im + im*b.re)
    operator fun times(v: Double) = Complex(re*v, im*v)
    val magnitude get() = hypot(re, im)
    val phase get() = atan2(im, re)
    companion object { fun polar(phase: Double) = Complex(cos(phase), sin(phase)) }
}
