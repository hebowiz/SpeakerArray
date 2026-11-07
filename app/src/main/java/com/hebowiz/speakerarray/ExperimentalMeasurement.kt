package com.hebowiz.speakerarray

import android.content.Context
import android.media.AudioDeviceInfo
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** Evaluates a fixed candidate. Contains no transfer estimator or FIR designer call. */
class ExperimentalMeasurement(context: Context, output: AudioDeviceInfo, input: AudioDeviceInfo,
    private val config: AutoFirSettings, private val original: Session, private val candidate: ExperimentalCandidate,
    private val progress: (String)->Unit, route: (MeasurementRouteState)->Unit,
    run: JSONObject=ExperimentalRuns.start(candidate)) : ExperimentalMeter {
    val audio=MeasurementAudio(context,output,input,config,route)
    val plan=ExperimentalPlan.forSettings(config)
    override val coefficientHash=getHash()
    private fun getHash()=processingHash(requireNotNull(candidate.processing))
    val diagnostic=run.put("type","speakerarray-experimental-comparison").put("schemaVersion",1)
        .put("candidateId",candidate.id).put("coefficientHash",coefficientHash).put("formalAccepted",false)
        .put("plan",plan.json()).put("settings",CalibrationRepository.settings(config)).put("masterDb",original.masterDb)
        .put("swapped",original.swapped).put("startedAtEpochMs",System.currentTimeMillis())
    private val silent=ExperimentalComparison.processing(candidate,ExperimentalCondition.DARK)
    private val dsp=DspEngine(PreparedProcessing.create(silent))
    private val source=SourceEngine(SourceSettings(kind=SourceKind.WHITE,low=plan.lowHz,high=plan.highHz,bandLimit=false),periodic=plan.noise())
    private var generation=0L
    private val l=DoubleArray(BLOCK);private val r=DoubleArray(BLOCK)
    private var waitSeconds=plan.minimumWait
    private var noiseFloor=1e-14
    private var calibrating=false
    private val trace=JSONArray();private val records=JSONArray()
    @Volatile private var renderFailure: String?=null
    private suspend fun check() {coroutineContext.ensureActive();audio.check();renderFailure?.let {error(it)};check(getHash()==coefficientHash) {"係数ハッシュが変更されました"}}
    override suspend fun measure(tag: String, condition: ExperimentalCondition): ExperimentalReading {
        check()
        val p=ExperimentalComparison.processing(candidate,condition);PlaybackSafety.requireSafe(p,original.masterDb)
        audio.discardCaptured();dsp.submit(PreparedProcessing.create(p,++generation))
        withTimeout(3000) {while(dsp.appliedGeneration!=generation || dsp.switching) {check();delay(5)}}
        val changed=System.nanoTime();val outputFrame=audio.writtenFrames;val origin=audio.capturedFrames
        val count=ceil(plan.windowSeconds*config.inputRate).toInt()
        var from=origin+ceil(waitSeconds*config.inputRate).toLong();val windows=ArrayDeque<BandWindow>()
        while(true) {
            check();require((from-origin).toDouble()/config.inputRate<=plan.maximumWait+2*plan.windowSeconds) {"整定タイムアウト・前条件の録音は採用しません"}
            while(audio.capturedFrames<from+count) {check();delay(10)}
            val window=ExperimentalPower.estimate(audio.captureRange(from,count),config.inputRate,plan)
            windows.addLast(window);if(windows.size>3) windows.removeFirst()
            trace.put(JSONObject().put("tag",tag).put("captureFrom",from).put("power",window.power).put("elapsedSeconds",(from-origin).toDouble()/config.inputRate))
            val start=from-2*count;val waited=(start-origin).toDouble()/config.inputRate
            if(ExperimentalSettling.ready(windows.toList(),waited,waitSeconds,noiseFloor,if(calibrating) condition!=ExperimentalCondition.DARK else null)) {
                val result=ExperimentalReading(tag,condition,windows.toList(),generation,changed,waited,outputFrame,start,from+count)
                records.put(result.json());return result
            }
            from+=count
        }
    }
    suspend fun run(): JSONObject = withTimeout(plan.maximumSeconds*1000L) {
        plan.validate(config.inputRate);candidate.validate();ExperimentalFir.validateProcessing(requireNotNull(candidate.processing),candidate.settings,original.masterDb)
        progress("比較用の入出力経路を無音で確認");audio.open()
        diagnostic.put("requested",audio.state().requested).put("actual",audio.state().actual).put("pcm",audio.state().pcm)
            .put("effects",audio.state().effects).put("systemVolumeIndex",audio.volumeIndex).put("audioMode",audio.mode)
        audio.beginPlayback({out ->
            source.render(l,r,750.0,0.0)
            check(l.all {it.isFinite() && abs(it)<=1} && r.all {it.isFinite() && abs(it)<=1}) {"音源PCM異常"}
            dsp.process(l,r,out,original.masterDb,original.swapped);check(dsp.clips==0L) {"出力クリップ"};true
        },{renderFailure=it})
        try {
            progress("帯域ノイズの立上り・消失を2回確認")
            val initial=measure("settleDark",ExperimentalCondition.DARK);noiseFloor=max(initial.high,1e-14)
            calibrating=true;var observed=waitSeconds
            repeat(2) {round->
                for(c in listOf(ExperimentalCondition.SINGLE,ExperimentalCondition.DARK)) {
                    val v=measure("settle$round-${c.name}",c)
                    observed=max(observed,v.waitedSeconds+plan.windows*plan.windowSeconds)
                }
            }
            waitSeconds=max(plan.minimumWait,observed+0.5)
            require(waitSeconds<=plan.maximumWait) {"往復遅延と整定が8秒を超えました"}
            calibrating=false;diagnostic.put("effectiveWaitSeconds",waitSeconds)
            val comparison=ExperimentalComparison(candidate,plan,progress)
            val result=comparison.run(this@ExperimentalMeasurement)
            result.keys().forEach {key->diagnostic.put(key,result.get(key))}
            diagnostic.put("stage","COMPLETED");diagnostic
        } finally {
            withContext(NonCancellable) {
                runCatching {dsp.submit(PreparedProcessing.create(silent,++generation));withTimeout(500) {while(dsp.appliedGeneration!=generation || dsp.switching) delay(5)}}
                audio.endPlayback()
            }
            diagnostic.put("settlingTrace",trace).put("coefficientHashAfter",getHash()).put("clips",audio.clips)
            if(!diagnostic.has("readings")) diagnostic.put("readings",records)
            else diagnostic.put("calibrationReadings",JSONArray((0 until min(5,records.length())).map {records.getJSONObject(it)}))
        }
    }
}
