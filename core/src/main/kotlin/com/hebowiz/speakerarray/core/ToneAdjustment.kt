package com.hebowiz.speakerarray.core

import kotlin.math.*

enum class AdjustmentMethod { TONE, FIR }

/** Fixed before acquisition. Neither frequency bandwidth nor search bounds follow a low reading. */
data class TonePlan(val frequency: Double, val referenceRight: Boolean = false,
    val windowSeconds: Double = 0.5, val minimumWaitSeconds: Double = 1.0,
    val maximumWaitSeconds: Double = 6.0, val maxSeconds: Int = 600) {
    val bandwidthHz get() = max(2.0,frequency*0.0006)
    val maxMeasurements get() = 64
    val estimatedSeconds get() = (maxMeasurements*(minimumWaitSeconds+3*windowSeconds)+30).toInt()
    fun validate(inputRate: Int) {
        finiteRange(frequency,80.0,min(16000.0,inputRate*0.44),"対象周波数 Hz")
        require(windowSeconds==0.5 && minimumWaitSeconds==1.0 && maximumWaitSeconds==6.0 && maxSeconds==600)
    }
    companion object { const val VERSION="tone-power-grid-v1" }
}

/** Gain always belongs to the correction channel; reference remains exactly 0 dB. */
data class TonePoint(val phaseDegrees: Double = 0.0, val correctionDb: Double = 0.0) {
    fun simple(plan: TonePlan) = SimpleCorrection(
        left=Channel(gainDb=if(plan.referenceRight) correctionDb else 0.0),
        right=Channel(gainDb=if(plan.referenceRight) 0.0 else correctionDb),
        sinePhaseDeg=wrapPhase(if(plan.referenceRight) -phaseDegrees else phaseDegrees),referenceHz=plan.frequency)
    fun safe(masterDb: Double) = correctionDb in -6.0..6.0 && max(1.0,dbGain(correctionDb))*dbGain(masterDb)<=0.9
}
enum class ToneCondition { DARK, SINGLE, BOTH, CANDIDATE }
data class ToneWindow(val power: Double, val residualPower: Double, val detectedHz: Double, val peak: Double)

object TonePower {
    /** DC + sine + cosine least squares on a fixed nine-frequency grid.
     * Noninteger periods need the full Gram matrix, not unnormalised FFT bins.
     */
    fun estimate(pcm: DoubleArray, rate: Int, plan: TonePlan): ToneWindow {
        require(pcm.size>100 && pcm.all {it.isFinite()}) {"PCM欠落"}
        val peak=pcm.maxOf {abs(it)};require(peak<0.999) {"MIC入力クリップ"}
        var best=ToneWindow(0.0,0.0,plan.frequency,peak)
        val mean=pcm.average();val variance=pcm.sumOf {(it-mean).pow(2)}/pcm.size
        for(j in -4..4) {
            val hz=plan.frequency+j*plan.bandwidthHz/4
            val angle=2*PI*hz/rate;val dc=cos(angle);val ds=sin(angle)
            var c=1.0;var s=0.0;var cc=0.0;var ss=0.0;var cs=0.0;var sc=0.0;var sn=0.0;var yc=0.0;var ys=0.0
            for(y in pcm) {
                cc+=c*c;ss+=s*s;cs+=c*s;sc+=c;sn+=s;yc+=(y-mean)*c;ys+=(y-mean)*s
                val next=c*dc-s*ds;s=s*dc+c*ds;c=next
            }
            cc-=sc*sc/pcm.size;ss-=sn*sn/pcm.size;cs-=sc*sn/pcm.size
            val det=cc*ss-cs*cs;require(det>1e-12)
            val a=(yc*ss-ys*cs)/det;val b=(ys*cc-yc*cs)/det
            val power=(a*a+b*b)/2
            if(power>=best.power) best=ToneWindow(power,(variance-(a*yc+b*ys)/pcm.size).coerceAtLeast(0.0),hz,peak)
        }
        return best
    }
}

data class ToneReading(val tag: String, val condition: ToneCondition, val point: TonePoint,
    val windows: List<ToneWindow>, val changedNano: Long, val waitedSeconds: Double,
    val outputFrame: Long, val captureFrom: Long, val captureUntil: Long) {
    val mean get() = windows.map {it.power}.average()
    val lower get() = windows.minOf {it.power}
    val upper get() = windows.maxOf {it.power}
    val spreadDb get() = powerDb(upper/lower.coerceAtLeast(1e-30))
}

object ToneSettling {
    /** Frames, not coroutine scheduling time, decide whether all three windows are fresh. */
    fun ready(windows: List<ToneWindow>, firstWindowSeconds: Double, minimumWait: Double,
        floor: Double, requiredSignal: Boolean? = null): Boolean {
        if(windows.size!=3 || firstWindowSeconds<minimumWait) return false
        val low=windows.minOf {it.power};val high=windows.maxOf {it.power}
        if(windows.any {!it.power.isFinite() || it.power<0 || it.peak>=0.999}) return false
        val stable=high<=floor*10 || powerDb(high/low.coerceAtLeast(1e-30))<=0.75
        val reached=when(requiredSignal) {true -> low>floor*10;false -> high<=floor*4;null -> true}
        return stable && reached
    }
}
fun powerDb(p: Double) = 10*log10(p.coerceAtLeast(1e-30))
data class ToneVerification(val singleLowerDb: Double, val bothLowerDb: Double, val driftDb: Double,
    val candidateSpreadDb: Double) {
    val passed get() = singleLowerDb>=3 && bothLowerDb>=3 && driftDb<=1 && candidateSpreadDb<=1.5
}
data class ToneOutcome(val point: TonePoint?, val verified: Boolean, val reason: String,
    val readings: List<ToneReading>, val verification: List<ToneVerification>, val edge: Boolean = false)

/** Transport owns continuous streams, freshness, settling, clip/route/PCM checks and deadlines. */
interface ToneMeter {
    suspend fun measure(tag: String, condition: ToneCondition, point: TonePoint): ToneReading
}

class ToneSearch(private val plan: TonePlan, private val masterDb: Double,
    private val progress: (String)->Unit = {}) {
    val readings=mutableListOf<ToneReading>()
    val verification=mutableListOf<ToneVerification>()
    var candidate: TonePoint?=null;private set
    private var noise=1e-30
    private suspend fun read(m: ToneMeter, tag: String, condition: ToneCondition, p: TonePoint=TonePoint()): ToneReading {
        require(readings.size<plan.maxMeasurements) {"候補数上限"}
        require(p.safe(masterDb)) {"出力ピーク上限"}
        val r=m.measure(tag,condition,p)
        // A transport cannot substitute an old condition, a partial window, or stale capture.
        require(r.tag==tag && r.point==p && r.condition==condition && r.windows.size>=3 &&
            r.waitedSeconds>=plan.minimumWaitSeconds && r.captureUntil>r.captureFrom &&
            (readings.isEmpty() || (r.changedNano>readings.last().changedNano && r.captureFrom>=readings.last().captureUntil))) {"古い設定の録音・PCM取得範囲不正"}
        require(r.windows.all {it.power.isFinite() && it.power>=0 && it.peak<0.999}) {"PCM異常・クリップ"}
        readings+=r
        if(condition!=ToneCondition.DARK) require(r.spreadDb<=1.5) {"録音レベル不安定: 評価窓の変動"}
        return r
    }
    private fun detected(r: ToneReading) = r.lower>noise*10
    private fun drift(a: ToneReading,b: ToneReading) = abs(powerDb(a.mean/b.mean.coerceAtLeast(1e-30)))
    private fun stable(a: ToneReading,b: ToneReading) {require(detected(b) && drift(a,b)<=1.0) {"録音レベル不安定: 基準再確認"}}
    suspend fun run(m: ToneMeter): ToneOutcome {
        progress("基準・暗騒音を測定")
        val dark=read(m,"dark",ToneCondition.DARK)
        noise=max(dark.upper,1e-14)
        val single=read(m,"initialSingle",ToneCondition.SINGLE)
        val both=read(m,"initialBoth",ToneCondition.BOTH)
        require(detected(single) && detected(both)) {"対象周波数の信号検出不足・測定限界"}
        var best: ToneReading?=null;var count=0
        suspend fun candidates(points: List<TonePoint>) {
            for(p in points.distinct()) {
                if(!p.safe(masterDb)) continue
                progress("探索中: ${p.phaseDegrees}° / ${p.correctionDb} dB")
                val r=read(m,"search${count++}",ToneCondition.CANDIDATE,p)
                // Readings below the floor are tied, never ranked by tiny random minima.
                if(best==null || max(r.mean,noise*10)<max(best!!.mean,noise*10)) best=r
                if(count%4==0) stable(single,read(m,"reference$count",ToneCondition.SINGLE))
            }
        }
        candidates((0..11).map {TonePoint(wrapPhase(it*30.0))})
        candidates((-6..6 step 2).map {TonePoint(best!!.point.phaseDegrees,it.toDouble())})
        val phase=best!!.point.phaseDegrees;val gain=best!!.point.correctionDb
        candidates((-3..3).map {TonePoint(wrapPhase(phase+it*5),gain)})
        val refined=best!!.point
        candidates((-2..2).map {TonePoint(refined.phaseDegrees,refined.correctionDb+it*0.5)}.filter {it.correctionDb in -6.0..6.0})
        val fixed=best!!.point;candidate=fixed
        val selected=fixed.simple(plan)
        progress("候補・未反映: 右／左 ${selected.ratioDb} dB / 右−左 ${selected.sinePhaseDeg}°・新しい録音で再検証")
        for(repeat in 0..1) {
            val s1=read(m,"verify${repeat}SingleBefore",ToneCondition.SINGLE)
            val sequence=if(repeat==0) listOf(ToneCondition.BOTH,ToneCondition.CANDIDATE) else listOf(ToneCondition.CANDIDATE,ToneCondition.BOTH)
            val values=sequence.associateWith {read(m,"verify${repeat}${it.name}",it,if(it==ToneCondition.CANDIDATE) fixed else TonePoint())}
            val b1=values.getValue(ToneCondition.BOTH);val c=values.getValue(ToneCondition.CANDIDATE)
            val b2=read(m,"verify${repeat}BothAfter",ToneCondition.BOTH)
            val s2=read(m,"verify${repeat}SingleAfter",ToneCondition.SINGLE)
            stable(single,s1);stable(single,s2);stable(both,b1);stable(both,b2)
            // Worst observed candidate + floor, lowest bracketing baseline - floor.
            val high=c.upper+noise
            val v=ToneVerification(powerDb((min(s1.lower,s2.lower)-noise).coerceAtLeast(0.0)/high),
                powerDb((min(b1.lower,b2.lower)-noise).coerceAtLeast(0.0)/high),max(drift(s1,s2),drift(b1,b2)),c.spreadDb)
            verification+=v
            if(!detected(c)) return ToneOutcome(fixed,false,"測定限界: 候補の信号をノイズ床から識別できません",readings.toList(),verification.toList())
        }
        val passed=verification.all {it.passed}
        return ToneOutcome(fixed,passed,if(passed) "検証済み" else "効果不足・未反映",readings.toList(),verification.toList(),abs(fixed.correctionDb)>=6)
    }
}

data class ToneProfile(val simple: SimpleCorrection, val frequency: Double, val masterDb: Double,
    val swapped: Boolean, val settings: AutoFirSettings, val volume: Int, val mode: Int,
    val layout: SpeakerLayout?, val reverseLandscape: Boolean, val backup: String, val result: String) {
    fun matches(s: Session, volume: Int, mode: Int, held: Boolean) = held && s.correctionMode==CorrectionMode.SIMPLE &&
        s.simple==simple && s.source.kind==SourceKind.SINE && s.source.frequency==frequency && s.masterDb==masterDb &&
        s.swapped==swapped && s.autoFir==settings && s.activeLayout==layout && s.reverseLandscape==reverseLandscape && this.volume==volume && this.mode==mode
}

object ToneTransaction {
    fun accept(before: Session, outcome: ToneOutcome, profile: ToneProfile): Session {
        require(outcome.verified && outcome.verification.size==2 && outcome.verification.all {it.passed})
        val point=requireNotNull(outcome.point)
        require(profile.simple==point.simple(TonePlan(profile.frequency,profile.settings.referenceRight)))
        return before.copy(simple=profile.simple,correctionMode=CorrectionMode.SIMPLE,
            source=before.source.copy(kind=SourceKind.SINE,frequency=profile.frequency),autoFir=profile.settings,toneProfile=profile).also {it.validate()}
    }
}
