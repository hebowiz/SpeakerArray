package com.hebowiz.speakerarray

import android.content.Context
import android.media.AudioDeviceInfo
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import org.json.JSONObject
import org.json.JSONArray

enum class AutoStage(val label: String) {
    IDLE("未測定"), ROUTING("経路確認"), MEASURING("測定中"), SEARCHING("探索中"), ANALYZING("同期・品質解析"), DESIGNING("設計中"),
    TRIAL("試験適用"), VERIFYING("効果確認"), CREATED("試聴候補作成済み"), APPLIED("適用完了"), REJECTED("未反映"), CANCELLED("取消・未反映")
}
data class AutoProgress(val stage: AutoStage=AutoStage.IDLE, val fraction: Float=0f, val detail: String="")
data class AutoMeasurementResult(val pair: MeasuredPair, val solution: FirSolution, val timing: TimingFit,
    val verification: List<Verification>, val single: Transfer, val both: Transfer, val corrected: Transfer,
    val route: MeasurementRouteState, val settings: AutoFirSettings, val volume: Int, val mode: Int,
    val startedAt: Long, val recordedFrames: Long, val evidence: AcceptanceEvidence, val diagnosticJson: String)

/** Diagnostics precede decisions; trial processing never mutates the user's session. */
class AutoFirMeasurement(context: Context, output: AudioDeviceInfo, input: AudioDeviceInfo,
    private val config: AutoFirSettings, private val original: Session,
    private val onProgress: (AutoProgress)->Unit, route: (MeasurementRouteState)->Unit,
    private val analysis: (String)->Unit = {}) {
    val audio=MeasurementAudio(context,output,input,config,route)
    var listeningInput: ExperimentalInput?=null; private set
    private var listening=false
    val diagnostics=JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",2)
        .put("thresholds",MeasurementResultStore.thresholds()).put("settings",CalibrationRepository.settings(config))
        .put("sampleRateHz",SAMPLE_RATE).put("transferOrder",JSONArray(listOf("L1","L2","R1","R2")))
        .put("masterDb",original.masterDb).put("frequencyStepHz",SAMPLE_RATE.toDouble()/TransferEstimator.SIZE)
        .put("originalSession",JSONObject(SessionRepository.encode(original.copy(calibration=null))))
        .put("analyzable",false).put("trialEligible",false).put("applicable",false)
    private fun progress(value: AutoProgress) {diagnostics.put("currentStage",value.stage.name);onProgress(value)}
    private data class Shot(val tag: String, val pilotStart: Long, val signalStart: Long, val pilotEnd: Long, val signal: DoubleArray)
    private val shots=mutableListOf<Shot>()
    private val order=JSONArray()
    private val warnings=mutableListOf<Finding>()
    private val pilot=KnownProbe.pilot(config)
    private var template: DoubleArray?=null
    private val gain=dbGain(original.masterDb)
    private val guard=((config.tailSeconds+0.2)*SAMPLE_RATE).toInt()
    private val pre=2400
    private val tail=((config.tailSeconds+0.1)*SAMPLE_RATE).toInt()
    private var darkSpectrum: DoubleArray?=null
    private var captureOrigin=0.0
    private fun publish() {diagnostics.put("warnings",MeasurementResultStore.findings(warnings)).put("measurementOrder",order);analysis(diagnostics.toString())}
    fun finalDiagnostics(): JSONObject = diagnostics.put("measurementOrder",order)
        .put("captureClock",JSONArray(audio.captureClocks().map {JSONObject().put("frame",it.frame).put("readFrames",it.readFrames).put("nanoTime",it.nanoTime)}))
    private fun reject(code: String, text: String): AutoMeasurementResult? {
        diagnostics.put("reasonCode",code).put("detail",text).put("stage",AutoStage.REJECTED.name);publish()
        progress(AutoProgress(AutoStage.REJECTED,detail=text));return null
    }
    private suspend fun check() {coroutineContext.ensureActive();audio.check()}
    private suspend fun emit(tag: String, signal: DoubleArray, right: Boolean?=null, processing: Processing?=null) {
        check()
        val start=pilot.size+guard;val end=start+signal.size+tail
        val frames=end+pilot.size+(config.latencySeconds*SAMPLE_RATE).toInt()+guard
        val stereo=FloatArray(frames*2)
        if(processing!=null) require(CalibrationPlaybackPolicy.peakBound(processing,original.masterDb)<=0.9) {"出力余裕不足"}
        fun put(frame: Int, physicalRight: Boolean, value: Double) {stereo[frame*2+if(physicalRight xor original.swapped) 1 else 0]=value.toFloat()}
        for(i in pilot.indices) {put(i,config.referenceRight,pilot[i]*gain);put(end+i,config.referenceRight,pilot[i]*gain)}
        if(processing==null) for(i in signal.indices) put(start+i,right!!,signal[i]*gain)
        else {
            val dsp=DspEngine(PreparedProcessing.create(processing))
            val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK);val out=FloatArray(BLOCK*2)
            repeat(32) {dsp.process(l,r,out,original.masterDb,original.swapped)}
            var offset=0
            while(offset<signal.size+tail) {
                check()
                for(i in 0 until BLOCK) {val v=signal.getOrElse(offset+i) {0.0};l[i]=v;r[i]=v}
                dsp.process(l,r,out,original.masterDb,original.swapped)
                require(dsp.clips==0L) {"候補FIRの出力がクリップしました"}
                val count=min(BLOCK,signal.size+tail-offset)
                System.arraycopy(out,0,stereo,(start+offset)*2,count*2);offset+=count
            }
        }
        val sent=audio.write(stereo);shots+=Shot(tag,sent,sent+start,sent+end,signal)
        order.put(JSONObject().put("tag",tag).put("pilotBefore",sent).put("signalFrame",sent+start).put("pilotAfter",sent+end)
            .put("signalFrames",signal.size).put("masterDb",original.masterDb))
    }
    private fun nominal(pcm: DoubleArray): DoubleArray {
        if(config.inputRate==SAMPLE_RATE) return pcm
        val ratio=config.inputRate.toDouble()/SAMPLE_RATE
        return DoubleArray((pcm.size/ratio).toInt()) {i->val p=i*ratio;if(p<16 || p>=pcm.size-17) 0.0 else MeasurementSync.sample(pcm,p)}
    }
    private fun snapshot(): DoubleArray {
        val data=audio.snapshot();captureOrigin=audio.captureOriginFrames.toDouble()*SAMPLE_RATE/config.inputRate;return nominal(data)
    }
    private suspend fun timing(pcm: DoubleArray, name: String): SyncAssessment {
        val emitted=shots.flatMap {listOf(it.pilotStart.toDouble(),it.pilotEnd.toDouble())}
        fun detect(t: Double, reference: DoubleArray): PilotDetection {
            val center=t-captureOrigin
            return MeasurementSync.detect(pcm,reference,max(0,(center-config.latencySeconds*SAMPLE_RATE).toInt()),
                min(pcm.size,(center+(config.latencySeconds+0.3)*SAMPLE_RATE+reference.size).toInt()))
        }
        if(template==null) {
            val first=detect(emitted.first(),pilot)
            diagnostics.put("initialPilot",JSONObject().put("correlation",first.correlation).put("position",first.position+captureOrigin))
            if(!listening) require(first.correlation>0.12) {"パイロットを識別できません"}
            require(first.position.isFinite()) {"パイロットの時刻を計算できません"}
            val raw=DoubleArray(pilot.size) {MeasurementSync.sample(pcm,first.position+it)}
            val n=powerOfTwo(raw.size);val re=raw.copyOf(n);val im=DoubleArray(n);Fft.transform(re,im)
            for(k in 0 until n) if(min(k,n-k)*SAMPLE_RATE.toDouble()/n !in config.lowHz..config.highHz) {re[k]=0.0;im[k]=0.0}
            Fft.transform(re,im,true);template=re.copyOf(raw.size)
        }
        val detections=emitted.map {t->check();val d=detect(t,template!!);d.copy(position=d.position+captureOrigin,competitorPosition=d.competitorPosition+captureOrigin)}
        val result=SyncPolicy.assess(emitted,detections,config.highHz)
        diagnostics.put("timing",MeasurementResultStore.sync(result)).put(name+"Timing",MeasurementResultStore.sync(result))
            .put("analyzable",result.analyzable).put("timestampChecks",audio.timestampChecks).put("timestampSupported",audio.timestampSupported)
        warnings+=result.findings.filter {it.verdict==Verdict.WARNING};publish();return result
    }
    private fun transfer(pcm: DoubleArray, fit: TimingFit, shot: Shot, noise: Double): Transfer {
        val captured=DoubleArray(pre+shot.signal.size+tail) {i->MeasurementSync.sample(pcm,fit.offset+fit.ratio*(shot.signalStart-pre+i)-captureOrigin)}
        val written=DoubleArray(captured.size) {i->if(i in pre until pre+shot.signal.size) shot.signal[i-pre]*gain else 0.0}
        return TransferEstimator.estimate(written,captured,noise,darkSpectrum)
    }
    private fun unDelay(t: Transfer): Transfer {
        val re=t.re.copyOf();val im=t.im.copyOf()
        for(k in re.indices) {val p=2*PI*k*(config.taps/2+16)/TransferEstimator.SIZE
            re[k]=t.re[k]*cos(p)-t.im[k]*sin(p);im[k]=t.re[k]*sin(p)+t.im[k]*cos(p)}
        return t.copy(re=re,im=im)
    }
    suspend fun run(forListening: Boolean=false): AutoMeasurementResult? = withTimeout(MeasurementLimits.MAX_SECONDS*1000L) {
        listening=forListening
        config.validate();val started=System.currentTimeMillis();diagnostics.put("startedAtEpochMs",started)
        progress(AutoProgress(AutoStage.ROUTING,0f,"MIC許可・入出力を確認中"));audio.open()
        diagnostics.put("requested",audio.state().requested).put("actual",audio.state().actual).put("pcm",audio.state().pcm)
            .put("effects",audio.state().effects).put("systemVolumeIndex",audio.volumeIndex).put("audioMode",audio.mode)
            .put("outputAttributes","USAGE_MEDIA / CONTENT_TYPE_MUSIC").put("codec","unknown")
        audio.silence(1.0+config.latencySeconds)
        val dark=snapshot();darkSpectrum=TransferEstimator.noiseSpectrum(dark.takeLast(SAMPLE_RATE).toDoubleArray())
        val noise=dark.takeLast(min(dark.size,SAMPLE_RATE)).map {it*it}.average().coerceAtLeast(1e-14)
        diagnostics.put("darkSpectrum",MeasurementResultStore.array(darkSpectrum!!))
        val signal=KnownProbe.generate(config)
        for(repeat in 0..1) for(right in listOf(false,true)) {
            progress(AutoProgress(AutoStage.MEASURING,0.05f+shots.size*0.04f,"${if(right) "右" else "左"} ${repeat+1}/2・前後パイロット付き"))
            emit(if(right) "R" else "L",signal,right)
        }
        audio.silence(config.latencySeconds+0.2)
        var pcm=snapshot()
        if(listening) {
            require(pcm.isNotEmpty() && pcm.all {it.isFinite()}) {"PCM欠落・非有限値"}
            require(pcm.none {abs(it)>=0.999}) {"MIC入力クリップ"}
            require(pcm.any {it!=0.0}) {"MIC録音がすべてゼロです"}
        } else MeasurementQuality.input(pcm)
        progress(AutoProgress(AutoStage.ANALYZING,0.25f,"パイロット時刻モデル・周波数別の品質を解析"))
        val initialSync=timing(pcm,"initial")
        if(!listening && !initialSync.analyzable) return@withTimeout reject("SYNC_ABORT",initialSync.findings.joinToString {it.message})
        require(initialSync.fit.offset.isFinite() && initialSync.fit.ratio.isFinite() && initialSync.fit.ratio>0 && initialSync.fit.residual.isFinite()) {"共通時刻モデルを計算できません"}
        val left=shots.filter {it.tag=="L"}.map {transfer(pcm,initialSync.fit,it,noise)}
        val right=shots.filter {it.tag=="R"}.map {transfer(pcm,initialSync.fit,it,noise)}
        val strict=TransferEstimator.assess(left,right,config,QualityPolicy.STRICT)
        val quality=TransferEstimator.assess(left,right,config,QualityPolicy.TRIAL);val pair=quality.pair
        diagnostics.put("strictQuality",MeasurementResultStore.pair(strict)).put("trialPolicy",MeasurementResultStore.policy(QualityPolicy.TRIAL))
            .put("strictPolicy",MeasurementResultStore.policy(QualityPolicy.STRICT))
        diagnostics.put("quality",MeasurementResultStore.pair(quality)).put("initialTransfers",JSONArray((left+right).map {MeasurementResultStore.transfer(it)}))
        MeasurementQuality.distinctChannels(pair)?.let {warnings+=it};publish()
        if(listening) {
            diagnostics.put("clips",audio.state().clips).put("stage",AutoStage.ANALYZING.name)
                .put("reasonCode",if(!quality.eligible) "BAND_QUALITY" else if(!initialSync.reliable) "SYNC_CONFIDENCE" else "POC_UNVERIFIED")
            listeningInput=ExperimentalFir.readDiagnostic(diagnostics,sha256(diagnostics.toString().toByteArray(Charsets.UTF_8)),true)
            return@withTimeout null
        }
        if(!quality.eligible) return@withTimeout reject("BAND_QUALITY",quality.findings.joinToString {it.message})
        if(!initialSync.reliable) return@withTimeout reject("SYNC_CONFIDENCE",initialSync.findings.joinToString {it.message})
        val mask=pair.weights.copyOf();diagnostics.put("evaluationMask",MeasurementResultStore.array(mask)).put("trialEligible",true)
        progress(AutoProgress(AutoStage.DESIGNING,0.35f,"有限長・因果FIRを最小二乗で設計"))
        val job=coroutineContext
        val solution=AutoFirDesigner.design(pair,config,original.masterDb) {job.ensureActive();audio.check()}
        diagnostics.put("solution",MeasurementResultStore.solution(solution));publish()
        val fixed=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(config.taps) {if(it==config.taps/2) 1.0 else 0.0})
        val both=Processing(length=config.taps,left=Channel(fir=fixed),right=Channel(fir=fixed))
        val single=if(config.referenceRight) both.copy(left=both.left.copy(muted=true)) else both.copy(right=both.right.copy(muted=true))
        val reference=if(config.referenceRight) pair.right else pair.left
        val verification=mutableListOf<Verification>();val verificationJson=JSONArray()
        var finalSingle=reference;var finalBoth=reference;var finalCorrected=reference;var finalSync=initialSync
        for((kind,probe) in listOf("designSignal" to signal,"bandNoise" to KnownProbe.noise(config))) {
            pcm=DoubleArray(0);shots.clear();audio.discardCaptured()
            for(repeat in 0..1) {
                progress(AutoProgress(AutoStage.VERIFYING,if(kind=="designSignal") 0.45f else 0.7f,"$kind ${repeat+1}/2・基準を前後に測定"))
                emit("singleBefore",probe,processing=single)
                val conditions=if(repeat==0) listOf("both" to both,"corrected" to solution.processing) else listOf("corrected" to solution.processing,"both" to both)
                for((tag,p) in conditions) {progress(AutoProgress(AutoStage.TRIAL,0.6f,"$kind / $tag ${repeat+1}/2・未反映"));emit(tag,probe,processing=p)}
                emit("singleAfter",probe,processing=single)
            }
            audio.silence(config.latencySeconds+0.2);pcm=snapshot();MeasurementQuality.input(pcm)
            finalSync=timing(pcm,kind)
            if(!finalSync.analyzable) return@withTimeout reject("VERIFY_SYNC_ABORT",finalSync.findings.joinToString {it.message})
            val transfers=shots.map {transfer(pcm,finalSync.fit,it,noise)}
            diagnostics.put(kind+"Transfers",JSONArray(transfers.map {MeasurementResultStore.transfer(it)}));publish()
            val baselines=shots.indices.filter {shots[it].tag.startsWith("single")}.map {transfers[it]}
            val stability=TransferEstimator.assess(listOf(reference)+baselines.map {unDelay(it)},listOf(reference)+baselines.map {unDelay(it)},config,QualityPolicy.TRIAL)
            diagnostics.put(kind+"ReferenceStability",MeasurementResultStore.pair(stability))
            val bothTransfers=shots.indices.filter {shots[it].tag=="both"}.map {transfers[it]}
            val bothStability=TransferEstimator.assess(bothTransfers,bothTransfers,config,QualityPolicy.TRIAL)
            diagnostics.put(kind+"BothStability",MeasurementResultStore.pair(bothStability))
            val preserved=mask.indices.all {mask[it]<=0 || (stability.quality.valid[it] && bothStability.quality.valid[it])}
            val local=mutableListOf<VerificationAssessment>()
            for(repeat in 0..1) {
                val batch=shots.indices.drop(repeat*4).take(4)
                val s=transfers[batch.first()];val b=transfers[batch.first {shots[it].tag=="both"}];val c=transfers[batch.first {shots[it].tag=="corrected"}]
                val v=MeasurementQuality.assessVerification(s,b,c,mask);local+=v
                verificationJson.put(JSONObject().put("signal",kind).put("repeat",repeat+1).put("assessment",MeasurementResultStore.verification(v)))
                v.result?.let {verification+=it};finalSingle=s;finalBoth=b;finalCorrected=c
            }
            val correctedTransfers=shots.indices.filter {shots[it].tag=="corrected"}.map {transfers[it]}
            val conservative=MeasurementQuality.conservative(baselines,bothTransfers,correctedTransfers,mask)
            diagnostics.put(kind+"Conservative",MeasurementResultStore.verification(conservative))
                .put("powerPolicy","fixed-mask-observed-envelope-plus-noise-v1")
            diagnostics.put("verification",verificationJson);publish()
            if(!conservative.passed) return@withTimeout reject("POWER_COMPARISON",conservative.findings.joinToString {it.message})
            if(!preserved) return@withTimeout reject("REFERENCE_CHANGED","前後基準の反復性が固定評価ビンで失われました。MIC位置・AGC等を確認してください")
            if(!finalSync.reliable) return@withTimeout reject("VERIFY_SYNC_CONFIDENCE","効果確認中の同期信頼度が不足しています")
            if(local.any {!it.passed}) return@withTimeout reject("INSUFFICIENT_REDUCTION",local.flatMap {it.findings}.joinToString {it.message})
        }
        check()
        val evidence=AcceptanceEvidence(true,true,true,true,true,true)
        diagnostics.put("applicable",true).put("stage",AutoStage.APPLIED.name).put("reasonCode","VERIFIED")
            .put("detail","${config.lowHz}–${config.highHz} Hz・2信号でMIC録音上の低減を確認。空間全体・任意音源は未評価")
            .put("playbackPath","Measured AudioTrack retained; USAGE_MEDIA / CONTENT_TYPE_MUSIC; same DSP coefficients")
        publish()
        AutoMeasurementResult(pair,solution,finalSync.fit,verification,finalSingle,finalBoth,finalCorrected,
            audio.state(),config,audio.volumeIndex,audio.mode,started,audio.state().frames,evidence,diagnostics.toString())
    }
}
