package com.hebowiz.speakerarray.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

enum class ExperimentalCondition { DARK, SINGLE, BOTH, CANDIDATE }
data class ExperimentalPlan(val lowHz: Double, val highHz: Double, val minimumWait: Double,
    val maximumWait: Double=8.0, val windowSeconds: Double=0.512, val windows: Int=3,
    val seed: Long=0x45585046, val maximumSeconds: Int=240) {
    fun validate(rate: Int) {
        require(lowHz>=80 && highHz>lowHz && highHz<rate*0.45)
        require(minimumWait in 2.0..6.0 && maximumWait==8.0 && windowSeconds==0.512 && windows==3 && maximumSeconds==240)
        require(22*(maximumWait+windows*windowSeconds)+20<=maximumSeconds) {"比較時間の上限を超えます"}
    }
    fun json()=JSONObject().put("lowHz",lowHz).put("highHz",highHz).put("minimumWaitSeconds",minimumWait)
        .put("maximumWaitSeconds",maximumWait).put("windowSeconds",windowSeconds).put("windows",windows).put("rounds",3)
        .put("maximumSeconds",maximumSeconds).put("seed",seed).put("periodFrames",8192).put("sourcePeak",0.8)
        .put("source","periodic random-phase equal-bin-power noise; no per-period fade; same continuous SourceEngine")
        .put("psd","8192 point rectangular (one full output period), nominally resampled to 48000 Hz; one-sided |FFT|²/(N*sum(window²)); linear average")
        .put("settling","two on/off steps; latest stable window end + 0.5s; applied generation and fresh capture frames")
        .put("baselineDriftLimitDb",2.0).put("candidateSpreadLimitDb",1.5)
    fun noise(): DoubleArray {
        val n=8192;val re=DoubleArray(n);val im=DoubleArray(n);val random=java.util.Random(seed)
        for(k in 1 until n/2) if(k*SAMPLE_RATE.toDouble()/n in lowHz..highHz) {
            val a=random.nextDouble()*2*PI;re[k]=cos(a);im[k]=sin(a);re[n-k]=re[k];im[n-k]=-im[k]
        }
        Fft.transform(re,im,true);val peak=re.maxOf {abs(it)};require(peak>0)
        return DoubleArray(n) {re[it]/peak*0.8}
    }
    companion object {fun forSettings(s: AutoFirSettings)=ExperimentalPlan(s.lowHz,s.highHz,max(2.0,s.latencySeconds+s.tailSeconds+0.1))}
}

data class BandWindow(val power: Double, val spectrum: List<Double>, val peak: Double)
object ExperimentalPower {
    fun estimate(pcm: DoubleArray, rate: Int, plan: ExperimentalPlan): BandWindow {
        require(pcm.isNotEmpty() && pcm.all {it.isFinite() && abs(it)<0.999}) {"PCM異常・クリップ"}
        // Only nominal sample-rate conversion, never pilot/phase alignment. Clock drift is not fitted away.
        val data=if(rate==SAMPLE_RATE) pcm else {
            val n=floor((pcm.size-34)*SAMPLE_RATE.toDouble()/rate).toInt()
            DoubleArray(n) {MeasurementSync.sample(pcm,17+it*rate.toDouble()/SAMPLE_RATE)}
        }
        val n=8192;require(data.size>=n) {"帯域電力の評価窓不足"}
        // A complete rectangular period is translation-invariant for this fixed periodic source.
        val window=DoubleArray(n) {1.0};val norm=n*window.sumOf {it*it}
        val sum=DoubleArray(n/2+1);var count=0
        for(start in 0..data.size-n step n) {
            val re=DoubleArray(n) {data[start+it]*window[it]};val im=DoubleArray(n);Fft.transform(re,im)
            for(k in sum.indices) sum[k]+=(re[k]*re[k]+im[k]*im[k])*(if(k==0 || k==n/2) 1 else 2)/norm
            count++
        }
        for(k in sum.indices) sum[k]/=count.toDouble()
        val power=sum.indices.filter {it*SAMPLE_RATE.toDouble()/n in plan.lowHz..plan.highHz}.sumOf {sum[it]}
        return BandWindow(power,sum.toList(),pcm.maxOf {abs(it)})
    }
}
data class ExperimentalReading(val tag: String, val condition: ExperimentalCondition, val windows: List<BandWindow>,
    val generation: Long, val changedNano: Long, val waitedSeconds: Double, val outputFrame: Long, val captureFrom: Long, val captureUntil: Long) {
    val mean get()=windows.map {it.power}.average()
    val low get()=windows.minOf {it.power}
    val high get()=windows.maxOf {it.power}
    val spectrum get()=List(4097) {k->windows.map {it.spectrum[k]}.average()}
    fun json()=JSONObject().put("tag",tag).put("condition",condition.name).put("generation",generation).put("changedNano",changedNano)
        .put("waitedSeconds",waitedSeconds).put("outputFrame",outputFrame).put("captureFrom",captureFrom).put("captureUntil",captureUntil)
        .put("windows",JSONArray(windows.map {JSONObject().put("power",it.power).put("peak",it.peak)})).put("spectrum",JSONArray(spectrum))
}
interface ExperimentalMeter {
    val coefficientHash: String
    suspend fun measure(tag: String, condition: ExperimentalCondition): ExperimentalReading
}
object ExperimentalSettling {
    fun ready(values: List<BandWindow>, waited: Double, minimumWait: Double, floor: Double, requiredSignal: Boolean?): Boolean {
        if(values.size!=3 || waited<minimumWait) return false
        val low=values.minOf {it.power};val high=values.maxOf {it.power}
        if(values.any {!it.power.isFinite() || it.power<0 || it.peak>=0.999}) return false
        val stable=high<=floor*10 || powerDb(high/low.coerceAtLeast(1e-30))<=0.75
        return stable && when(requiredSignal) {true->low>floor*10;false->high<=floor*4;null->true}
    }
}
class ExperimentalComparison(val candidate: ExperimentalCandidate, val plan: ExperimentalPlan,
    private val progress: (String)->Unit={}) {
    val readings=mutableListOf<ExperimentalReading>()
    private val hash=requireNotNull(candidate.coefficientHash)
    suspend fun run(meter: ExperimentalMeter): JSONObject {
        require(candidate.playable)
        suspend fun read(tag: String, condition: ExperimentalCondition): ExperimentalReading {
            check(meter.coefficientHash==hash) {"比較中に係数が変更されました"}
            progress(tag)
            val r=meter.measure(tag,condition)
            require(r.tag==tag && r.condition==condition && r.windows.size==plan.windows && r.waitedSeconds>=plan.minimumWait &&
                r.captureUntil>r.captureFrom && (readings.isEmpty() || (r.captureFrom>=readings.last().captureUntil &&
                    r.changedNano>readings.last().changedNano && r.generation>readings.last().generation && r.outputFrame>readings.last().outputFrame))) {"旧条件の録音・取得範囲・世代が不正です"}
            require(r.windows.all {w->w.power.isFinite() && w.power>=0 && w.peak<0.999 && w.spectrum.size==4097 && w.spectrum.all {it.isFinite() && it>=0}}) {"PCM電力不正"}
            readings+=r;check(meter.coefficientHash==hash) {"比較後の係数ハッシュ不一致"};return r
        }
        read("darkBefore",ExperimentalCondition.DARK)
        val orders=listOf(listOf("S","B","C","B","S"),listOf("B","S","C","S","B"),listOf("S","B","C","S","B"))
        for((round,order) in orders.withIndex()) for((i,c) in order.withIndex()) read("round${round+1}-$i-$c",when(c) {
            "S"->ExperimentalCondition.SINGLE;"B"->ExperimentalCondition.BOTH;else->ExperimentalCondition.CANDIDATE})
        read("darkAfter",ExperimentalCondition.DARK)
        check(meter.coefficientHash==hash)
        return evaluate(readings,candidate,plan).put("coefficientHashAfter",meter.coefficientHash)
    }
    companion object {
        fun processing(c: ExperimentalCandidate,condition: ExperimentalCondition): Processing {
            val p=requireNotNull(c.processing);val flat=Channel(fir=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(p.length) {if(it==p.length/2) 1.0 else 0.0}))
            val both=Processing(length=p.length,left=flat,right=flat)
            return when(condition) {ExperimentalCondition.CANDIDATE->p;ExperimentalCondition.BOTH->both
                ExperimentalCondition.SINGLE->both.withSolo(c.settings.referenceRight)
                ExperimentalCondition.DARK->both.copy(left=flat.copy(muted=true),right=flat.copy(muted=true))}
        }
        fun evaluate(readings: List<ExperimentalReading>,c: ExperimentalCandidate,plan: ExperimentalPlan): JSONObject {
            val dark=readings.filter {it.condition==ExperimentalCondition.DARK};require(dark.size==2)
            val floor=dark.maxOf {it.high}.coerceAtLeast(1e-20)
            val single=readings.filter {it.condition==ExperimentalCondition.SINGLE};val both=readings.filter {it.condition==ExperimentalCondition.BOTH}
            val corrected=readings.filter {it.condition==ExperimentalCondition.CANDIDATE};require(single.size==6 && both.size==6 && corrected.size==3)
            fun spread(v: List<ExperimentalReading>)=powerDb(v.maxOf {it.high}/v.minOf {it.low}.coerceAtLeast(1e-30))
            val baselineDrift=max(spread(single),spread(both));val candidateSpread=spread(corrected)
            val darkDrift=powerDb(dark.maxOf {it.high}.coerceAtLeast(1e-20)/dark.minOf {it.low}.coerceAtLeast(1e-20))
            val limited=corrected.any {it.low<=10*floor};val evaluable=single.all {it.low>10*floor} && both.all {it.low>10*floor}
            val stable=baselineDrift<=2 && (candidateSpread<=1.5 || limited) && darkDrift<=3
            fun result(base: List<ExperimentalReading>,corr: List<ExperimentalReading>): JSONObject {
                val valid=base.all {it.low>10*floor}
                val ratio=if(valid) powerDb(base.map {it.mean}.average()/corr.map {it.mean}.average().coerceAtLeast(floor)) else null
                val lower=if(valid) powerDb((base.minOf {it.low}-floor)/(corr.maxOf {it.high}+floor)) else null
                return JSONObject().put("pointDb",ratio ?: JSONObject.NULL).put("lowerDb",lower ?: JSONObject.NULL)
                    .put("noiseLimited",corr.any {it.low<=10*floor}).put("evaluable",valid)
            }
            val rounds=JSONArray((0..2).map {r->val group=readings.filter {it.tag.startsWith("round${r+1}-")}
                val cand=group.filter {it.condition==ExperimentalCondition.CANDIDATE}
                JSONObject().put("round",r+1).put("order",JSONArray(group.map {it.condition.name}))
                    .put("single",result(group.filter {it.condition==ExperimentalCondition.SINGLE},cand))
                    .put("both",result(group.filter {it.condition==ExperimentalCondition.BOTH},cand))})
            val bins=ExperimentalFir.requestBins(c.settings)
            fun spectrum(v: List<ExperimentalReading>,k: Int)=v.map {it.spectrum[k]}.average()
            val noise=DoubleArray(4097) {k->dark.maxOf {it.spectrum[k]}.coerceAtLeast(1e-24)}
            fun curve(base: List<ExperimentalReading>)=JSONArray(bins.map {k->val b=spectrum(base,k);val v=spectrum(corrected,k)
                if(b<=noise[k]*10) JSONObject.NULL else powerDb(b/max(v,noise[k]))})
            val singleResult=result(single,corrected);val bothResult=result(both,corrected)
            val observed=evaluable && stable && singleResult.getDouble("lowerDb")>=3 && bothResult.getDouble("lowerDb")>=3
            return JSONObject().put("type","speakerarray-experimental-comparison").put("schemaVersion",1).put("candidateId",c.id)
                .put("coefficientHash",c.coefficientHash).put("formalAccepted",false).put("plan",plan.json()).put("rounds",rounds)
                .put("single",singleResult).put("both",bothResult).put("noiseFloorPower",floor).put("noiseDriftDb",darkDrift)
                .put("baselineDriftDb",baselineDrift).put("candidateSpreadDb",candidateSpread).put("evaluable",evaluable).put("stable",stable)
                .put("status",if(!evaluable) "評価不能・基準がノイズ床付近" else if(!stable) "参考値・再現性不足" else if(observed) "実験比較で低減を観測・正式合格ではありません" else "比較結果あり・3 dB未満または増音")
                .put("noiseLimited",limited).put("readings",JSONArray(readings.map {it.json()}))
                .put("hz",JSONArray(bins.map {it*SAMPLE_RATE/8192.0})).put("singleDb",curve(single)).put("bothDb",curve(both))
                .put("lowSnr",JSONArray(bins.map {min(spectrum(single,it),spectrum(both,it))<=10*noise[it]}))
                .put("outsideDesign",JSONArray(bins.map {c.weights[it]<=0}))
                .put("designAuxiliary",JSONObject().apply {
                    val selected=bins.filter {c.weights[it]>0}
                    put("bins",selected.size)
                    val n=selected.sumOf {noise[it]};val cp=selected.sumOf {spectrum(corrected,it)}
                    for((name,base) in listOf("singleDb" to single,"bothDb" to both)) {
                        val b=selected.sumOf {spectrum(base,it)}
                        put(name,if(b>10*n && selected.isNotEmpty()) powerDb(b/max(cp,n)) else JSONObject.NULL)
                    }
                })
        }
    }
}
