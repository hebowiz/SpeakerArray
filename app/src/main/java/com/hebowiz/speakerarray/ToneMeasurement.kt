package com.hebowiz.speakerarray

import android.content.Context
import android.media.AudioDeviceInfo
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** One oscillator and the same SourceEngine/DspEngine path used by ordinary SIMPLE playback. */
class ToneMeasurement(context: Context, output: AudioDeviceInfo, input: AudioDeviceInfo,
    val config: AutoFirSettings, private val original: Session,
    private val progress: (AutoProgress)->Unit, route: (MeasurementRouteState)->Unit,
    private val publish: (String)->Unit) : ToneMeter {
    val plan=TonePlan(original.autoToneHz,config.referenceRight)
    val audio=MeasurementAudio(context,output,input,config,route)
    val diagnostics=JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",3)
        .put("method","TONE").put("policy",TonePlan.VERSION).put("frequencyHz",plan.frequency)
        .put("settings",CalibrationRepository.settings(config)).put("masterDb",original.masterDb)
        .put("swapped",original.swapped).put("applicable",false).put("codec","unknown")
        .put("plan",JSONObject().put("windowSeconds",plan.windowSeconds).put("windows",3)
            .put("bandwidthHz",plan.bandwidthHz).put("frequencyGridPoints",9).put("minimumWaitSeconds",plan.minimumWaitSeconds)
            .put("maximumWaitSeconds",plan.maximumWaitSeconds).put("maximumMeasurements",plan.maxMeasurements)
            .put("maximumSeconds",plan.maxSeconds).put("gainRangeDb",JSONArray(listOf(-6,6)))
            .put("coarsePhaseStepDegrees",30).put("powerComparison","min(bracketing baseline windows)-noise / max(candidate windows)+noise; >=3 dB twice"))
    private val records=JSONArray()
    private val settlingTrace=JSONArray()
    private var generation=0L
    private val flat=Processing(length=original.processing.length,left=Channel(muted=true),right=Channel(muted=true))
    private val dsp=DspEngine(PreparedProcessing.create(flat,0,true))
    private val source=SourceEngine(original.source.copy(kind=SourceKind.SINE,frequency=plan.frequency))
    private val l=DoubleArray(BLOCK);private val r=DoubleArray(BLOCK)
    @Volatile private var renderFailure: String?=null
    private var waitSeconds=plan.minimumWaitSeconds
    private var darkFloor=1e-14
    private var calibrating=false
    private suspend fun check() {coroutineContext.ensureActive();audio.check();renderFailure?.let {error(it)}}
    private suspend fun window(from: Long): ToneWindow {
        val count=(plan.windowSeconds*config.inputRate).toInt()
        while(audio.capturedFrames<from+count) {check();delay(10)}
        return TonePower.estimate(audio.captureRange(from,count),config.inputRate,plan)
    }
    override suspend fun measure(tag: String, condition: ToneCondition, point: TonePoint): ToneReading {
        check();require(point.safe(original.masterDb)) {"出力ピーク上限"}
        var p=point.simple(plan).processing(original.processing.length,true)
        p=when(condition) {
            ToneCondition.DARK -> p.copy(left=p.left.copy(muted=true),right=p.right.copy(muted=true))
            ToneCondition.SINGLE -> p.withSolo(plan.referenceRight)
            else -> p
        }
        val prepared=PreparedProcessing.create(p,++generation,true)
        audio.discardCaptured()
        dsp.submit(prepared)
        withTimeout(3000) {while(dsp.appliedGeneration!=generation || dsp.switching) {check();delay(5)}}
        val changed=System.nanoTime();val outputFrame=audio.writtenFrames;val origin=audio.capturedFrames
        val windowFrames=(plan.windowSeconds*config.inputRate).toInt()
        var start=origin+ceil(waitSeconds*config.inputRate).toLong()
        val values=ArrayDeque<ToneWindow>()
        while(true) {
            check()
            require((start-origin).toDouble()/config.inputRate<=plan.maximumWaitSeconds+2*plan.windowSeconds) {"録音レベル不安定: 整定タイムアウト"}
            val value=window(start);values.addLast(value)
            if(values.size>3) values.removeFirst()
            val elapsed=(start+windowFrames-origin).toDouble()/config.inputRate
            settlingTrace.put(JSONObject().put("tag",tag).put("captureFrom",start).put("elapsedSeconds",elapsed).put("power",value.power))
            val requiredSignal=if(calibrating) condition!=ToneCondition.DARK else null
            if(ToneSettling.ready(values.toList(),(start-2*windowFrames-origin).toDouble()/config.inputRate,
                    waitSeconds,darkFloor,requiredSignal)) {
                val from=start-2*windowFrames
                val reading=ToneReading(tag,condition,point,values.toList(),changed,(from-origin).toDouble()/config.inputRate,
                    outputFrame,from,start+windowFrames)
                records.put(encode(reading));diagnostics.put("readings",records).put("settlingTrace",settlingTrace)
                publish(diagnostics.toString())
                return reading
            }
            start+=windowFrames
        }
    }
    suspend fun run(): ToneOutcome = withTimeout(plan.maxSeconds*1000L) {
        plan.validate(config.inputRate)
        diagnostics.put("startedAtEpochMs",System.currentTimeMillis())
        progress(AutoProgress(AutoStage.ROUTING,detail="MIC・出力経路を無音で確認"));audio.open()
        diagnostics.put("requested",audio.state().requested).put("actual",audio.state().actual).put("pcm",audio.state().pcm)
            .put("effects",audio.state().effects).put("systemVolumeIndex",audio.volumeIndex).put("audioMode",audio.mode)
        audio.beginPlayback({out ->
            source.render(l,r,plan.frequency,0.0,quadrature=true)
            dsp.process(l,r,out,original.masterDb,original.swapped)
            check(dsp.clips==0L) {"出力クリップ"};true
        },{renderFailure=it})
        try {
            progress(AutoProgress(AutoStage.MEASURING,detail="単一周波数の立上り・消失と整定時間を確認"))
            // Use a conservative silence guard initially, then observe both step directions twice.
            waitSeconds=max(plan.minimumWaitSeconds,config.latencySeconds+config.tailSeconds)
            val dark=measure("latencyDark",ToneCondition.DARK,TonePoint());darkFloor=max(dark.upper,1e-14)
            calibrating=true
            var observed=waitSeconds
            repeat(2) {i ->
                for(c in listOf(ToneCondition.SINGLE,ToneCondition.DARK)) {
                    val r=measure("latency${i}${c.name}",c,TonePoint())
                    observed=max(observed,r.waitedSeconds+3*plan.windowSeconds)
                }
            }
            calibrating=false
            waitSeconds=max(observed+0.5,config.latencySeconds+config.tailSeconds+0.03)
            require(waitSeconds<=plan.maximumWaitSeconds) {"往復遅延・整定時間が上限を超えました"}
            diagnostics.put("effectiveWaitSeconds",waitSeconds).put("latencyRule","two on/off steps; latest stable end +0.5 s; configured latency+tail+fade lower bound")
            val search=ToneSearch(plan,original.masterDb) {detail ->
                val candidate="候補" in detail
                progress(AutoProgress(if(candidate) AutoStage.VERIFYING else AutoStage.SEARCHING,
                    (records.length().toFloat()/plan.maxMeasurements).coerceAtMost(0.95f),detail))
            }
            val outcome=try {search.run(this@ToneMeasurement)} finally {
                search.candidate?.let {diagnostics.put("candidate",point(it))}
                diagnostics.put("verification",JSONArray(search.verification.map {verification(it)}))
            }
            diagnostics.put("applicable",outcome.verified).put("reasonCode",if(outcome.verified) "VERIFIED" else "INSUFFICIENT_REDUCTION")
                .put("detail",outcome.reason).put("edgeOfGainRange",outcome.edge)
            outcome
        } finally {
            // Fade to zero on the same DSP; cancellation still gets a bounded cleanup interval.
            withContext(NonCancellable) {
                runCatching {dsp.submit(PreparedProcessing.create(flat,++generation,true));withTimeout(300) {while(dsp.appliedGeneration!=generation) delay(5)}}
                audio.endPlayback()
            }
            diagnostics.put("readings",records).put("settlingTrace",settlingTrace).put("clips",audio.clips)
            publish(diagnostics.toString())
        }
    }
    private fun point(p: TonePoint)=JSONObject().put("correctionChannelPhaseDegrees",p.phaseDegrees).put("correctionChannelGainDb",p.correctionDb)
        .put("rightMinusLeftPhaseDegrees",p.simple(plan).sinePhaseDeg).put("rightOverLeftDb",p.simple(plan).ratioDb)
    private fun encode(r: ToneReading)=JSONObject().put("tag",r.tag).put("condition",r.condition.name).put("point",point(r.point))
        .put("changedNano",r.changedNano).put("waitedSeconds",r.waitedSeconds).put("outputFrame",r.outputFrame)
        .put("captureFrom",r.captureFrom).put("captureUntil",r.captureUntil).put("meanPower",r.mean).put("spreadDb",r.spreadDb)
        .put("windows",JSONArray(r.windows.map {JSONObject().put("power",it.power).put("residualPower",it.residualPower).put("detectedHz",it.detectedHz).put("peak",it.peak)}))
    private fun verification(v: ToneVerification)=JSONObject().put("singleLowerDb",v.singleLowerDb).put("bothLowerDb",v.bothLowerDb)
        .put("driftDb",v.driftDb).put("candidateSpreadDb",v.candidateSpreadDb).put("passed",v.passed)
}
