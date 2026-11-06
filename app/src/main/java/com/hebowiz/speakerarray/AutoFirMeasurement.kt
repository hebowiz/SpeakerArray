package com.hebowiz.speakerarray

import android.content.Context
import android.media.AudioDeviceInfo
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import org.json.JSONObject
import org.json.JSONArray

enum class AutoStage(val label: String) {
    IDLE("待機"), ROUTING("経路確認"), MEASURING("測定中"), DESIGNING("設計中"),
    TRIAL("試験適用"), VERIFYING("効果確認"), APPLIED("適用完了"), REJECTED("品質不足・未反映"), CANCELLED("取消・未反映")
}
data class AutoProgress(val stage: AutoStage=AutoStage.IDLE, val fraction: Float=0f, val detail: String="")
data class AutoMeasurementResult(val pair: MeasuredPair, val solution: FirSolution, val timing: TimingFit,
    val verification: List<Verification>, val single: Transfer, val both: Transfer, val corrected: Transfer,
    val route: MeasurementRouteState, val settings: AutoFirSettings, val volume: Int, val mode: Int,
    val startedAt: Long, val recordedFrames: Long)

/** All heavy work runs on a worker dispatcher; one owner is responsible for close/retention. */
class AutoFirMeasurement(context: Context, output: AudioDeviceInfo, input: AudioDeviceInfo,
                         private val config: AutoFirSettings, private val original: Session,
                         private val progress: (AutoProgress)->Unit, route: (MeasurementRouteState)->Unit) {
    val audio=MeasurementAudio(context,output,input,config,route)
    val diagnostics=JSONObject()
    private data class Shot(val tag: String, val pilotStart: Long, val signalStart: Long)
    private val shots=mutableListOf<Shot>()
    private val signal=KnownProbe.generate(config)
    private val pilot=KnownProbe.pilot(config)
    private val gain=dbGain(original.masterDb)
    private val guard=((config.tailSeconds+0.2)*SAMPLE_RATE).toInt()
    private val pre=2400 // common 50 ms margin preserves relative advances as well as delays
    private val tail=((config.tailSeconds+0.1)*SAMPLE_RATE).toInt()
    private var darkSpectrum: DoubleArray?=null

    private suspend fun check() {coroutineContext.ensureActive();audio.check()}
    private suspend fun emit(tag: String, right: Boolean?=null, processing: Processing?=null) {
        check()
        val frames=pilot.size+guard+signal.size+tail+(config.latencySeconds*SAMPLE_RATE).toInt()
        val stereo=FloatArray(frames*2)
        fun put(frame: Int, physicalRight: Boolean, value: Double) {
            stereo[frame*2+if(physicalRight xor original.swapped) 1 else 0]=value.toFloat()
        }
        for(i in pilot.indices) put(i,config.referenceRight,pilot[i]*gain)
        val start=pilot.size+guard
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
        val sent=audio.write(stereo)
        shots+=Shot(tag,sent,sent+start)
    }
    private fun nominalCapture(pcm: DoubleArray): DoubleArray {
        if(config.inputRate==SAMPLE_RATE) return pcm
        val ratio=config.inputRate.toDouble()/SAMPLE_RATE
        // Upsampling only for acquisition; input is rate-limited to <=48 kHz.
        return DoubleArray((pcm.size/ratio).toInt()) {i ->
            val p=i*ratio
            if(p<16 || p>=pcm.size-17) 0.0 else MeasurementSync.sample(pcm,p)
        }
    }
    private suspend fun timing(pcm: DoubleArray): TimingFit {
        val offsets=shots.map {s ->
            check()
            MeasurementSync.locate(pcm,pilot, max(0,(s.pilotStart-config.latencySeconds*SAMPLE_RATE).toInt()),
                min(pcm.size,(s.pilotStart+(config.latencySeconds+0.5)*SAMPLE_RATE+pilot.size).toInt()))
        }
        val fit=MeasurementSync.estimate(shots.map {it.pilotStart.toDouble()},offsets)
        diagnostics.put("timing",JSONObject().put("offset48k",fit.offset).put("clockPpm",fit.ppm)
            .put("residual48k",fit.residual).put("emittedFrames",JSONArray(fit.emitted)).put("capturedFrames48k",JSONArray(fit.captured)))
        MeasurementSync.validate(fit)
        return fit
    }
    private fun transfer(pcm: DoubleArray, fit: TimingFit, shot: Shot, noise: Double): Transfer {
        val captured=DoubleArray(pre+signal.size+tail) {i -> MeasurementSync.sample(pcm,fit.offset+fit.ratio*(shot.signalStart-pre+i))}
        val written=DoubleArray(captured.size) {i ->if(i in pre until pre+signal.size) signal[i-pre]*gain else 0.0}
        return TransferEstimator.estimate(written,captured,noise,darkSpectrum)
    }
    suspend fun run(): AutoMeasurementResult {
        config.validate();val started=System.currentTimeMillis()
        progress(AutoProgress(AutoStage.ROUTING,0f,"MIC許可・入出力を確認中"));audio.open()
        audio.silence(1.0+config.latencySeconds)
        val dark=audio.snapshot();MeasurementQuality.input(dark)
        darkSpectrum=TransferEstimator.noiseSpectrum(nominalCapture(dark).takeLast(SAMPLE_RATE).toDoubleArray())
        val noise=dark.takeLast(min(dark.size,config.inputRate)).map {it*it}.average().coerceAtLeast(1e-14)
        for(repeat in 0..1) for(right in listOf(false,true)) {
            progress(AutoProgress(AutoStage.MEASURING,0.05f+shots.size*0.08f,"${if(right) "右" else "左"} ${repeat+1}/2・同一セッションで収録"))
            emit(if(right) "R" else "L",right)
        }
        audio.silence(config.latencySeconds+0.2)
        var pcm=nominalCapture(audio.snapshot());MeasurementQuality.input(pcm)
        val firstFit=timing(pcm)
        val pair=TransferEstimator.pair(shots.filter {it.tag=="L"}.map {transfer(pcm,firstFit,it,noise)},
            shots.filter {it.tag=="R"}.map {transfer(pcm,firstFit,it,noise)},config)
        MeasurementQuality.distinctChannels(pair)
        progress(AutoProgress(AutoStage.DESIGNING,0.4f,"有限長・因果FIRを最小二乗で設計"))
        val job=coroutineContext
        val solution=AutoFirDesigner.design(pair,config,original.masterDb) {job.ensureActive();audio.check()}
        val fixed=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(config.taps) {if(it==config.taps/2) 1.0 else 0.0})
        val both=Processing(length=config.taps,left=Channel(fir=fixed),right=Channel(fir=fixed))
        val single=if(config.referenceRight) both.copy(left=both.left.copy(muted=true)) else both.copy(right=both.right.copy(muted=true))
        for(repeat in 0..1) {
            progress(AutoProgress(AutoStage.VERIFYING,0.5f+repeat*0.2f,"片側基準・未補正両側 ${repeat+1}/2"))
            emit("single",processing=single);emit("both",processing=both)
            progress(AutoProgress(AutoStage.TRIAL,0.6f+repeat*0.2f,"候補FIRを試験適用 ${repeat+1}/2・まだ未反映"))
            emit("corrected",processing=solution.processing)
        }
        audio.silence(config.latencySeconds+0.2)
        pcm=DoubleArray(0) // Release the first capture before allocating the final snapshot.
        pcm=nominalCapture(audio.snapshot());MeasurementQuality.input(pcm)
        val finalFit=timing(pcm)
        // Re-estimate both channels with a single final clock fit; never independently align L/R.
        val finalPair=TransferEstimator.pair(shots.filter {it.tag=="L"}.map {transfer(pcm,finalFit,it,noise)},
            shots.filter {it.tag=="R"}.map {transfer(pcm,finalFit,it,noise)},config)
        val singles=shots.filter {it.tag=="single"}.map {transfer(pcm,finalFit,it,noise)}
        val boths=shots.filter {it.tag=="both"}.map {transfer(pcm,finalFit,it,noise)}
        val corrected=shots.filter {it.tag=="corrected"}.map {transfer(pcm,finalFit,it,noise)}
        // Monitor the same reference before/after trial, including gain/phase repeatability.
        TransferEstimator.pair(singles,singles,config)
        val reference=if(config.referenceRight) finalPair.right else finalPair.left
        val uncompensated=singles.map {t ->
            val re=t.re.copyOf();val im=t.im.copyOf()
            for(k in re.indices) {
                val phase=2*PI*k*(config.taps/2+16)/TransferEstimator.SIZE
                re[k]=t.re[k]*cos(phase)-t.im[k]*sin(phase);im[k]=t.re[k]*sin(phase)+t.im[k]*cos(phase)
            }
            Transfer(re,im,t.snrDb)
        }
        TransferEstimator.pair(listOf(reference)+uncompensated,listOf(reference)+uncompensated,config)
        progress(AutoProgress(AutoStage.VERIFYING,0.95f,"帯域別低減量・反復性・録音ゲイン変化を判定"))
        val verification=(0..1).map {MeasurementQuality.verify(singles[it],boths[it],corrected[it],finalPair.weights)}
        check()
        return AutoMeasurementResult(finalPair,solution,finalFit,verification,singles.last(),boths.last(),corrected.last(),
            audio.state(),config,audio.volumeIndex,audio.mode,started,audio.state().frames)
    }
}
