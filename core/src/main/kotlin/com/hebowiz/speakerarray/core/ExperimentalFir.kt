package com.hebowiz.speakerarray.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
fun processingHash(p: Processing): String {
    // A fixed binary representation is independent of JSON field order and whitespace.
    val out=java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(out).use {d ->
        d.writeInt(p.length);d.writeDouble(p.delayUs);d.writeDouble(p.phaseDeg)
        for(c in listOf(p.left,p.right)) {
            d.writeDouble(c.gainDb);d.writeBoolean(c.muted);d.writeBoolean(c.inverted);d.writeBoolean(c.enabled)
            FirDesigner.design(c.fir,p.length).forEach {d.writeDouble(it)}
        }
    }
    return sha256(out.toByteArray())
}
internal fun JSONArray.doublesArray(expected: Int): DoubleArray {
    require(length()==expected) {"配列長が不正: $expected ビンが必要です"}
    return DoubleArray(expected) {getDouble(it).also {v->require(v.isFinite()) {"非有限値・欠損データ"}}}
}
internal fun jsonNumbers(a: DoubleArray)=JSONArray(a.map {if(it.isFinite()) it else JSONObject.NULL})

data class ExperimentalCandidate(val id: String, val sourceHash: String, val method: String,
    val settings: AutoFirSettings, val masterDb: Double, val processing: Processing?, val weights: List<Double>,
    val report: String, val error: String?=null, val comparisons: List<String> = emptyList()) {
    val coefficientHash get()=processing?.let(::processingHash)
    val playable get()=processing!=null && error==null
    fun validate() {
        settings.validate();finiteRange(masterDb,-60.0,0.0,"設計時共通出力")
        require(id.length in 1..160 && sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(weights.size==4097 && weights.all {it.isFinite() && it in 0.0..1.0})
        require(comparisons.size<=3 && report.length<=1_000_000 && comparisons.all {it.length<=4_000_000})
        processing?.let {it.validate();require(error==null)}
    }
}
data class ExperimentalLibrary(val candidates: List<ExperimentalCandidate> = emptyList(), val selected: String?=null,
    val applied: String?=null, val undo: String?=null) {
    fun current()=candidates.find {it.id==selected}
    fun validate() {
        require(candidates.size<=12 && candidates.map {it.id}.distinct().size==candidates.size) {"候補は最大12件です。保存後に候補一覧を整理してください"}
        candidates.forEach {it.validate()}
        require(candidates.sumOf {it.report.length+it.comparisons.sumOf(String::length)}<=24_000_000) {"候補履歴の保存上限です。JSONへ保存してから一覧を整理してください"}
        require(selected==null || candidates.any {it.id==selected});require(applied==null || candidates.any {it.id==applied})
        require(undo==null || undo.length<=SessionRepository.MAX_SESSION_CHARS)
    }
}

data class ExperimentalInput(val settings: AutoFirSettings, val masterDb: Double, val left: List<Transfer>,
    val right: List<Transfer>, val sourceHash: String, val notes: List<String>, val timing: String, val originalStage: String)

object ExperimentalFir {
    const val VERSION="experimental-finite-fir-v1"
    private val allowedSync=setOf("PILOT_AMBIGUOUS","LEGACY_SYNC_RESIDUAL")
    /** Schema 2 was written by AutoFirMeasurement: measurementOrder L,R,L,R, transfer array L1,L2,R1,R2. */
    fun readDiagnostic(raw: String): ExperimentalInput {
        require(raw.length<=16_000_000) {"診断JSONが大きすぎます"}
        val d=JSONObject(raw.removePrefix("\uFEFF"))
        require(d.getString("type")=="speakerarray-diagnostic" && d.getInt("schemaVersion")==2 && d.optString("method")!="TONE") {"対応するFIR診断スキーマ2が必要です"}
        require(d.getDouble("frequencyStepHz")==SAMPLE_RATE.toDouble()/TransferEstimator.SIZE) {"周波数刻み不明・不一致"}
        if(d.has("sampleRateHz")) require(d.getInt("sampleRateHz")==SAMPLE_RATE) {"応答レート不一致"}
        require(d.getLong("clips")==0L) {"PCMクリップの診断は使用できません"}
        val s=CalibrationRepository.readSettings(d.getJSONObject("settings"));val master=d.getDouble("masterDb")
        finiteRange(master,-60.0,0.0,"設計時共通出力")
        val order=d.getJSONArray("measurementOrder")
        require(order.length()>=4 && (0..3).map {order.getJSONObject(it).getString("tag")}==listOf("L","R","L","R")) {"左右・反復の測定順序を確認できません"}
        var end=-1L
        for(i in 0..3) {val o=order.getJSONObject(i);val a=o.getLong("pilotBefore");val b=o.getLong("signalFrame");val c=o.getLong("pilotAfter")
            require(a>end && b>a && c>=b+o.getInt("signalFrames") && o.getInt("signalFrames")>0) {"測定フレーム順序が不正です"};end=c}
        if(d.has("transferOrder")) require(d.getJSONArray("transferOrder").toString()==JSONArray(listOf("L1","L2","R1","R2")).toString()) {"応答配列の順序が不明です"}
        val timing=d.getJSONObject("initialTiming")
        require(timing.getString("method")==SyncPolicy.VERSION && timing.getBoolean("analyzable")) {"共通時刻モデルを使用できません"}
        for(key in listOf("offset48k","ratio","clockPpm","residual48k","rmsResidual48k")) require(timing.getDouble(key).isFinite()) {"時刻モデルの欠損・非有限値"}
        require(abs(timing.getDouble("clockPpm"))<=MeasurementLimits.MAX_CLOCK_PPM && abs((timing.getDouble("ratio")-1)*1e6-timing.getDouble("clockPpm"))<0.01) {"異常なクロック比"}
        require(timing.getDouble("residual48k") in 0.0..SAMPLE_RATE/(4*s.highHz)) {"TIME_MODEL_UNRESOLVED: 共通時刻モデルの残差が大きすぎます"}
        val findings=timing.getJSONArray("findings")
        val codes=(0 until findings.length()).map {findings.getJSONObject(it).getString("code")}
        require(codes.all {it in allowedSync}) {"生成禁止の同期条件: ${codes.filter {it !in allowedSync}}"}
        require(timing.getBoolean("reliable") || "PILOT_AMBIGUOUS" in codes) {"同期信頼度不足の原因が不明です"}
        require(timing.getJSONArray("excluded").length()==0 && timing.getJSONArray("pilots").length()>=8) {"パイロット欠損・選別あり"}
        val pilots=timing.getJSONArray("pilots")
        for(i in 0 until pilots.length()) require(pilots.getJSONObject(i).getDouble("correlation")>=0.12) {"パイロット識別不能"}
        // Later failures such as verification PCM loss also invalidate this diagnostic as a generation source.
        require(d.optString("reasonCode") in listOf("BAND_QUALITY","SYNC_CONFIDENCE","VERIFIED","","INSUFFICIENT_REDUCTION","POWER_COMPARISON","REFERENCE_CHANGED")) {"中止診断: ${d.optString("reasonCode")}"}
        val a=d.getJSONArray("initialTransfers");require(a.length()==4) {"左右各2回の複素応答が必要です"}
        val t=(0..3).map {i->val v=a.getJSONObject(i)
            Transfer(v.getJSONArray("real").doublesArray(4097),v.getJSONArray("imaginary").doublesArray(4097),v.getJSONArray("snrDb").doublesArray(4097),v.getJSONArray("recordedPower").doublesArray(4097))}
        require(t.all {v->v.recordedPower.all {it>=0} && requestBins(s).any {v.magnitude(it)>1e-8 && v.snrDb[it]>=20}}) {"無信号・識別可能な応答なし"}
        val notes=codes+listOf("暫定応答・共通時刻モデルを使用。左右個別整列なし", "スキーマ2の応答順 L1,L2,R1,R2・測定順 L1,R1,L2,R2", "両反復から推定した共通時刻モデルを共有。交差予測は完全独立ではありません")
        return ExperimentalInput(s,master,t.take(2),t.drop(2),sha256(raw.toByteArray(Charsets.UTF_8)),notes,timing.toString(),d.optString("stage"))
    }
    fun requestBins(s: AutoFirSettings)=(0..4096).filter {it*SAMPLE_RATE.toDouble()/8192 in s.lowHz..s.highHz}
    fun runs(bins: List<Int>): List<List<Int>> {
        val runs=mutableListOf<MutableList<Int>>()
        for(k in bins) {if(runs.isEmpty() || runs.last().last()+1!=k) runs+=mutableListOf(k) else runs.last()+=k}
        return runs
    }
    fun generate(input: ExperimentalInput, method: String="aggregate", settings: AutoFirSettings=input.settings,
                 nonce: String=UUID.randomUUID().toString(), check: ()->Unit={}): ExperimentalCandidate {
        require(method in listOf("aggregate","repeat1-to-2","repeat2-to-1"));settings.validate()
        require(settings.inputRate==input.settings.inputRate && settings.referenceRight==input.settings.referenceRight &&
            settings.output==input.settings.output && settings.input==input.settings.input) {"設計時の端点・基準ch・入力レートは元診断を維持してください"}
        val assessment=TransferEstimator.assess(input.left,input.right,settings,QualityPolicy.TRIAL)
        val train=if(method=="repeat2-to-1") 1 else 0
        val pair=if(method=="aggregate") assessment.pair else {
            // Duplicating the TRAINING observation only obtains the existing SNR taper; no evaluation response enters weights.
            TransferEstimator.assess(List(2) {input.left[train]},List(2) {input.right[train]},settings,QualityPolicy.TRIAL).pair
        }
        val bins=pair.weights.indices.filter {pair.weights[it]>0};val requested=requestBins(settings)
        val notes=input.notes+assessment.findings.map {it.message}+if(method!="aggregate") listOf("単独反復の設計では反復性を評価していません。評価側のSNR・低減量で設計ビンを選別していません") else emptyList()
        val report=JSONObject().put("policy",VERSION).put("originalStage",input.originalStage).put("formalAccepted",false)
            .put("timing",JSONObject(input.timing)).put("notes",JSONArray(notes)).put("trialCoverage",assessment.pair.coverage)
            .put("designBins",JSONArray(bins)).put("designBinCount",bins.size).put("requestedBinCount",requested.size)
            .put("designFraction",bins.size.toDouble()/requested.size.coerceAtLeast(1)).put("runs",JSONArray(runs(bins).map {JSONArray(listOf(it.first()*SAMPLE_RATE/8192.0,it.last()*SAMPLE_RATE/8192.0,it.size.toDouble()))}))
        val id=sha256((input.sourceHash+method+CalibrationRepository.settings(settings).toString()+nonce).toByteArray()).take(24)
        var p: Processing?=null;var error: String?=null
        try {
            check();require(bins.size>=20) {"重みが正の設計ビンが20未満: ${bins.size}"}
            val solution=AutoFirDesigner.design(pair,settings,input.masterDb,check)
            validateProcessing(solution.processing,settings,input.masterDb)
            p=solution.processing
            report.put("maxBoostDb",boostUpperDb(p,settings.referenceRight)).put("boostGridSize",32768).put("boostBoundMethod","dense FFT plus derivative bound between grid points").put("dspPeakBound",PlaybackSafety.peakBound(p,input.masterDb))
                .put("commonDelayFrames",p.length/2+16).put("coefficientHash",processingHash(p))
            val evaluations=if(method=="aggregate") listOf(0,1) else listOf(1-train)
            report.put("predictions",JSONArray(evaluations.map {r->predict(p,input.left[r],input.right[r],pair.weights,settings).put("evaluationRepeat",r+1)}))
        } catch(e: Exception) {check();error=e.message ?: "候補生成失敗";report.put("failure",error)}
        return ExperimentalCandidate(id,input.sourceHash,method,settings,input.masterDb,p,pair.weights.toList(),report.toString(),error)
    }
    fun validateProcessing(p: Processing,s: AutoFirSettings,master: Double) {
        p.validate();require(p.length==s.taps && p.delayUs==0.0 && p.phaseDeg==0.0) {"候補の遅延・位相条件が不正です"}
        val ref=if(s.referenceRight) p.right else p.left
        val flat=List(p.length) {if(it==p.length/2) 1.0 else 0.0}
        require(ref.gainDb==0.0 && !ref.muted && !ref.inverted && ref.enabled && FirDesigner.design(ref.fir,p.length).toList()==flat) {"基準chは非ゼロ・0 dB・共通遅延で固定してください"}
        val correction=if(s.referenceRight) p.left else p.right
        require(correction.gainDb==0.0 && !correction.inverted && correction.enabled) {"候補へ別のゲイン・極性を重ねられません"}
        require(boostUpperDb(p,s.referenceRight)<=s.maxBoostDb+1e-7) {"全周波数ブースト上限超過"}
        PlaybackSafety.requireSafe(p,master)
    }
    fun boostUpperDb(p: Processing, referenceRight: Boolean): Double {
        val correction=if(referenceRight) p.left else p.right
        val h=FirDesigner.design(correction.fir,p.length)
        val re=h.copyOf(32768);val im=DoubleArray(re.size);Fft.transform(re,im)
        val sampled=(0..re.size/2).maxOf {hypot(re[it],im[it])}
        // Factor out the common center delay; |dH/dω| <= Σ |n-center| |h[n]|.
        val between=PI/re.size*h.indices.sumOf {abs(it-p.length/2)*abs(h[it])}
        return levelDb(sampled+between)
    }
    fun predict(p: Processing,l: Transfer,r: Transfer,weights: DoubleArray,s: AutoFirSettings): JSONObject {
        val bins=requestBins(s);val single=DoubleArray(4097);val both=DoubleArray(4097);val candidate=DoubleArray(4097)
        val confident=BooleanArray(4097)
        for(k in bins) {
            val hz=k*SAMPLE_RATE/8192.0;val a=Complex(l.re[k],l.im[k]);val b=Complex(r.re[k],r.im[k])
            val delay=Complex.polar(-2*PI*hz*(p.length/2+16)/SAMPLE_RATE)
            single[k]=((if(s.referenceRight) b else a)*delay).magnitude.pow(2)
            both[k]=((a+b)*delay).magnitude.pow(2)
            candidate[k]=(a*ResponseAnalyzer.channel(p,false,hz,false)+b*ResponseAnalyzer.channel(p,true,hz,false)).magnitude.pow(2)
            confident[k]=min(l.snrDb[k],r.snrDb[k])>=20
        }
        fun group(selected: List<Int>): JSONObject {
            fun reduction(base: DoubleArray): Any {val b=selected.sumOf {base[it]};val c=selected.sumOf {candidate[it]};return if(b>1e-24 && c>1e-24) 10*log10(b/c) else JSONObject.NULL}
            return JSONObject().put("bins",selected.size).put("singleDb",reduction(single)).put("bothDb",reduction(both))
                .put("lowSnrBins",selected.count {!confident[it]}).put("confidence",if(selected.all {confident[it]}) "SNR>=20" else "信頼度不足を含む・除外なし")
        }
        fun ratios(base: DoubleArray)=JSONArray(bins.map {k->if(base[k]>1e-24 && candidate[k]>1e-24) 10*log10(base[k]/candidate[k]) else JSONObject.NULL})
        return JSONObject().put("type","finite-FIR-prediction-not-measurement").put("spectrum","equal energy per requested 48000/8192 Hz bin; no postselection")
            .put("fullBand",group(bins)).put("designBins",group(bins.filter {weights[it]>0}))
            .put("hz",JSONArray(bins.map {it*SAMPLE_RATE/8192.0})).put("singleDb",ratios(single)).put("bothDb",ratios(both))
            .put("lowSnr",JSONArray(bins.map {!confident[it]})).put("outsideDesign",JSONArray(bins.map {weights[it]<=0}))
    }
}

object ExperimentalRepository {
    fun candidate(c: ExperimentalCandidate): JSONObject=JSONObject().put("id",c.id).put("sourceHash",c.sourceHash).put("method",c.method)
        .put("settings",CalibrationRepository.settings(c.settings)).put("masterDb",c.masterDb)
        .put("processing",c.processing?.let {SessionRepository.processing(it)} ?: JSONObject.NULL).put("coefficientHash",c.coefficientHash ?: JSONObject.NULL)
        .put("weights",JSONArray(c.weights)).put("report",JSONObject(c.report)).put("error",c.error ?: JSONObject.NULL)
        .put("comparisons",JSONArray(c.comparisons.map {JSONObject(it)}))
    fun readCandidate(o: JSONObject): ExperimentalCandidate {
        val c=ExperimentalCandidate(o.getString("id"),o.getString("sourceHash"),o.getString("method"),CalibrationRepository.readSettings(o.getJSONObject("settings")),
            o.getDouble("masterDb"),o.optJSONObject("processing")?.let {SessionRepository.readProcessing(it)},o.getJSONArray("weights").doublesArray(4097).toList(),
            o.getJSONObject("report").toString(),if(o.isNull("error")) null else o.getString("error"),o.getJSONArray("comparisons").let {a->List(a.length()) {a.getJSONObject(it).toString()}})
        c.validate();c.processing?.let {ExperimentalFir.validateProcessing(it,c.settings,c.masterDb)};require(c.coefficientHash==if(o.isNull("coefficientHash")) null else o.getString("coefficientHash")) {"係数ハッシュ不一致"}
        c.comparisons.forEach {val result=JSONObject(it)
            require(!result.getBoolean("formalAccepted") && result.getString("coefficientHash")==c.coefficientHash && result.getString("candidateId")==c.id) {"別候補または正式結果を実験結果に流用できません"}
            if(result.has("coefficientHashAfter")) require(result.getString("coefficientHashAfter")==c.coefficientHash) {"比較後の係数ハッシュ不一致"}
        }
        return c
    }
    fun library(v: ExperimentalLibrary)=JSONObject().put("candidates",JSONArray(v.candidates.map(::candidate))).put("selected",v.selected ?: JSONObject.NULL)
        .put("applied",v.applied ?: JSONObject.NULL).put("undo",v.undo?.let(::JSONObject) ?: JSONObject.NULL)
    fun readLibrary(o: JSONObject)=ExperimentalLibrary(o.getJSONArray("candidates").let {a->List(a.length()) {readCandidate(a.getJSONObject(it))}},
        if(o.isNull("selected")) null else o.getString("selected"),if(o.isNull("applied")) null else o.getString("applied"),o.optJSONObject("undo")?.toString()).also {it.validate()}
    fun export(c: ExperimentalCandidate)=JSONObject().put("type","speakerarray-experimental-fir").put("schemaVersion",1).put("sampleRateHz",SAMPLE_RATE).put("candidate",candidate(c)).toString()
    fun import(raw: String): ExperimentalCandidate {require(raw.length<=16_000_000);val o=JSONObject(raw)
        require(o.getString("type")=="speakerarray-experimental-fir" && o.getInt("schemaVersion")==1 && o.getInt("sampleRateHz")==SAMPLE_RATE)
        return readCandidate(o.getJSONObject("candidate"))}
}
