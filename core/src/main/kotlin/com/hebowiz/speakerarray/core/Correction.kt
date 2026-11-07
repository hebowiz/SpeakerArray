package com.hebowiz.speakerarray.core

import kotlin.math.*

enum class CorrectionMode { FIR, SIMPLE }
/** FIR slot remains Session.processing, including all legacy manual adjustments. */
data class SimpleCorrection(val left: Channel = Channel(), val right: Channel = Channel(),
    val sinePhaseDeg: Double = 0.0, val sineDelayUs: Double = 0.0,
    val delayUs: Double = 0.0, val referenceHz: Double = 1143.0) {
    fun processing(length: Int, sine: Boolean) = Processing(length,
        left.copy(fir=FirSpec(),enabled=true),right.copy(fir=FirSpec(),enabled=true),
        if(sine) sineDelayUs else delayUs,if(sine) sinePhaseDeg else 0.0)
    fun validate() {
        processing(513,true).validate();processing(513,false).validate()
        finiteRange(referenceHz,20.0,20000.0,"基準周波数 Hz")
    }
    val ratioDb get() = right.gainDb-left.gainDb
    val ratio get() = dbGain(ratioDb)
    val polarityDeg get() = (if(right.inverted) 180.0 else 0.0)-(if(left.inverted) 180.0 else 0.0)
    fun phaseAt(hz: Double, sine: Boolean) = (if(sine) sinePhaseDeg else 0.0)+polarityDeg-360*hz*(if(sine) sineDelayUs else delayUs)/1e6
    companion object {
        fun from(p: Processing, hz: Double) = SimpleCorrection(p.left.copy(fir=FirSpec(),enabled=true),
            p.right.copy(fir=FirSpec(),enabled=true),p.phaseDeg,p.delayUs,p.delayUs,hz)
    }
}
data class CorrectionSnapshot(val mode: CorrectionMode, val fir: Processing, val simple: SimpleCorrection,
    val source: SourceSettings, val calibration: CalibrationProfile?, val toneProfile: ToneProfile? = null)
data class SpeakerLayout(val spacingMm: Double = 150.0, val reversed: Boolean = false)
fun Session.effectiveProcessing(): Processing = if(correctionMode==CorrectionMode.FIR) processing else simple.processing(processing.length,source.kind==SourceKind.SINE)
fun Session.correctionSnapshot() = CorrectionSnapshot(correctionMode,processing,simple,source,calibration,toneProfile)
fun Session.restore(s: CorrectionSnapshot) = copy(correctionMode=s.mode,processing=s.fir,simple=s.simple,source=s.source,calibration=s.calibration,toneProfile=s.toneProfile,a=if(processing.length==s.fir.length) a else null,b=if(processing.length==s.fir.length) b else null)
fun Session.changeSource(next: SourceSettings): Session {
    next.validate()
    // Keep the original oscillator setting; conversion never replaces polarity with delay.
    val converted=if(source.kind==SourceKind.SINE && next.kind!=SourceKind.SINE)
        simple.copy(delayUs=simple.sineDelayUs-simple.sinePhaseDeg/(360*source.frequency)*1e6,referenceHz=source.frequency)
        else simple
    converted.validate() // Reject out-of-range conversion atomically, without clipping.
    return copy(source=next,simple=converted)
}
fun Session.editActive(change: (Processing)->Processing): Session {
    if(correctionMode==CorrectionMode.FIR) return copy(processing=change(processing))
    val p=change(effectiveProcessing());p.validate()
    val next=simple.copy(left=p.left.copy(fir=FirSpec(),enabled=true),right=p.right.copy(fir=FirSpec(),enabled=true))
    return copy(simple=if(source.kind==SourceKind.SINE) next.copy(sinePhaseDeg=p.phaseDeg,sineDelayUs=p.delayUs) else next.copy(delayUs=p.delayUs))
}

fun Processing.withSolo(rightSide: Boolean?): Processing = rightSide?.let {r->copy(left=left.copy(muted=r || left.muted),right=right.copy(muted=!r || right.muted))} ?: this
