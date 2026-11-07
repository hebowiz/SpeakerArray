package com.hebowiz.speakerarray.core

import kotlin.math.*

enum class Verdict { PASS, WARNING, INSUFFICIENT, ABORT }
data class Finding(val code: String, val verdict: Verdict, val message: String)
data class PilotDetection(val position: Double, val correlation: Double, val peak: Double,
    val competitorPosition: Double, val competitorRatio: Double, val uncertaintySamples: Double)
data class SyncAssessment(val fit: TimingFit, val pilots: List<PilotDetection>, val findings: List<Finding>,
    val used: List<Int>, val excluded: List<Int> = emptyList()) {
    val analyzable get() = findings.none {it.verdict == Verdict.ABORT}
    val reliable get() = analyzable && findings.none {it.verdict == Verdict.INSUFFICIENT}
}
data class BandSuggestion(val lowHz: Double, val highHz: Double)
data class FrequencyQuality(val leftRepeatDb: DoubleArray, val rightRepeatDb: DoubleArray,
    val leftRepeatPhase: DoubleArray, val rightRepeatPhase: DoubleArray, val reasons: List<List<String>>,
    val valid: BooleanArray, val suggestion: BandSuggestion?)
data class PairAssessment(val pair: MeasuredPair, val quality: FrequencyQuality, val findings: List<Finding>) {
    val eligible get() = findings.none {it.verdict == Verdict.ABORT || it.verdict == Verdict.INSUFFICIENT}
}
data class VerificationAssessment(val result: Verification?, val findings: List<Finding>) {
    val passed get() = result != null && findings.none {it.verdict == Verdict.ABORT || it.verdict == Verdict.INSUFFICIENT}
}
data class AcceptanceEvidence(val timingReliable: Boolean, val qualityPassed: Boolean,
    val fixedMaskPreserved: Boolean, val referenceStable: Boolean, val alternateSignalPassed: Boolean,
    val playbackPathVerified: Boolean) {
    val accepted get() = timingReliable && qualityPassed && fixedMaskPreserved && referenceStable &&
        alternateSignalPassed && playbackPathVerified
}

/** All pilots are retained: robust outlier rejection must not hide a clock discontinuity. */
object SyncPolicy {
    const val VERSION = "common-template-16x-v2"
    const val MIN_PILOTS = 6
    const val MIN_CORRELATION = 0.65
    const val MAX_COMPETITOR = 0.9
    fun assess(emitted: List<Double>, pilots: List<PilotDetection>, highHz: Double,
               continuous: Boolean = true): SyncAssessment {
        val fit=MeasurementSync.estimate(emitted,pilots.map {it.position})
        val findings=mutableListOf<Finding>()
        if(!continuous) findings+=Finding("PCM_DISCONTINUITY",Verdict.ABORT,"PCM連続性を確認できません")
        if(!fit.ratio.isFinite() || abs(fit.ppm)>MeasurementLimits.MAX_CLOCK_PPM)
            findings+=Finding("CLOCK_RANGE",Verdict.ABORT,"共通クロック比が許容範囲外です")
        if(pilots.any {it.correlation<0.12}) findings+=Finding("PILOT_UNIDENTIFIED",Verdict.ABORT,"パイロット識別不能")
        if(pilots.size<MIN_PILOTS || emitted.last()-emitted.first()<2*SAMPLE_RATE)
            findings+=Finding("PILOT_COVERAGE",Verdict.INSUFFICIENT,"6点以上・2秒以上のパイロットが必要です")
        if(pilots.any {it.correlation<MIN_CORRELATION || it.competitorRatio>MAX_COMPETITOR})
            findings+=Finding("PILOT_AMBIGUOUS",Verdict.INSUFFICIENT,"パイロット波形の再現性または競合ピークに問題があります")
        // A quarter-cycle at the requested high edge is a coarse discontinuity guard,
        // not an accuracy certificate. Fine accuracy is checked by repeated complex responses.
        val coarse=SAMPLE_RATE/(4*highHz)
        val residual=fit.signedResiduals
        // Compare a common-slope step model, requiring >=3 observations on each side.
        // An isolated bad peak is NOT silently discarded or called a transport jump.
        val confident=pilots.all {it.correlation>=MIN_CORRELATION && it.competitorRatio<=MAX_COMPETITOR}
        var jumpDetected=false
        for(split in 3..pilots.size-3) {
            val groups=listOf(0 until split,split until pilots.size)
            val centers=groups.map {g->g.map {emitted[it]}.average() to g.map {pilots[it].position}.average()}
            var xy=0.0;var xx=0.0
            for((j,g) in groups.withIndex()) for(i in g) {val dx=emitted[i]-centers[j].first;xy+=dx*(pilots[i].position-centers[j].second);xx+=dx*dx}
            val slope=xy/xx
            val offsets=centers.map {it.second-slope*it.first}
            val jump=abs(offsets[1]-offsets[0])
            val error=groups.withIndex().maxOf {(j,g)->g.maxOf {abs(pilots[it].position-offsets[j]-slope*emitted[it])}}
            if(confident && jump>coarse && error<jump*0.2 && error<fit.residual*0.25) jumpDetected=true
        }
        if(jumpDetected) findings+=Finding("TIME_AXIS_DISCONTINUITY",Verdict.ABORT,"持続する時刻段差を検出しました。外れ値を除外せず中止しました")
        else if(fit.residual>coarse) findings+=Finding("TIME_MODEL_UNRESOLVED",Verdict.INSUFFICIENT,"時刻モデルの偏差が大きいため周波数別診断のみ続けます。時間軸の異常は未解決です")
        if(fit.residual>MeasurementLimits.MAX_SYNC_RESIDUAL)
            findings+=Finding("LEGACY_SYNC_RESIDUAL",Verdict.WARNING,"パイロット時刻モデルの残差が旧0.35 sampleを超えました。周波数別の反復性を評価します")
        return SyncAssessment(fit,pilots,findings,pilots.indices.toList())
    }
}

/** Playback safety is independent of saved measurement evidence. PCM must also be checked live. */
object PlaybackSafety {
    fun requireSafe(p: Processing, masterDb: Double) {
        p.validate();finiteRange(masterDb,-60.0,0.0,"共通出力 dB")
        check(peakBound(p,masterDb)<=0.9) {"出力ピーク上限を超える設定です。共通出力を下げてください"}
    }
    fun peakBound(p: Processing, masterDb: Double): Double {
        fun bound(c: Channel,right: Boolean): Double {
            val h=if(c.enabled) FirDesigner.design(c.fir,p.length) else FirDesigner.flat(p.length)
            val d=FractionalDelay.kernel(max(0.0,if(right) p.delayUs else -p.delayUs)*SAMPLE_RATE/1e6)
            val combined=DoubleArray(h.size+d.size-1)
            for(i in h.indices) for(j in d.indices) combined[i+j]+=h[i]*d[j]
            return if(c.muted) 0.0 else combined.sumOf {abs(it)}*dbGain(c.gainDb)
        }
        return max(bound(p.left,false),bound(p.right,true))*dbGain(masterDb)
    }
}

/** Describes current verification only; never a prerequisite for ordinary playback. */
object CalibrationPlaybackPolicy {
    fun peakBound(p: Processing, masterDb: Double) = PlaybackSafety.peakBound(p,masterDb)
    fun matches(profile: CalibrationProfile, session: Session, volume: Int, mode: Int, held: Boolean) =
        profile.matches(session,volume) && profile.mode==mode && held && peakBound(session.processing,session.masterDb)<=0.9
}
