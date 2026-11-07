package com.hebowiz.speakerarray.core

import org.json.JSONArray
import org.json.JSONObject

object SessionRepository {
    const val MAX_SESSION_CHARS = 64_000_000
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun numbers(v: List<Double>) = JSONArray(v)
    private fun JSONArray.doubles() = List(length()) { getDouble(it) }
    fun firObject(s: FirSpec): JSONObject = obj("mode" to s.mode.name, "eq" to numbers(s.eq), "low" to s.low, "high" to s.high, "coefficients" to numbers(s.coefficients))
    private fun readSpec(o: JSONObject) = FirSpec(FirMode.valueOf(o.getString("mode")), o.getJSONArray("eq").doubles(), o.getDouble("low"), o.getDouble("high"), o.getJSONArray("coefficients").doubles())
    private fun channel(c: Channel) = obj("gainDb" to c.gainDb, "muted" to c.muted, "inverted" to c.inverted, "enabled" to c.enabled, "fir" to firObject(c.fir))
    private fun readChannel(o: JSONObject) = Channel(o.getDouble("gainDb"), o.getBoolean("muted"), o.getBoolean("inverted"), o.getBoolean("enabled"), readSpec(o.getJSONObject("fir")))
    internal fun processing(p: Processing) = obj("length" to p.length, "left" to channel(p.left), "right" to channel(p.right), "delayUs" to p.delayUs, "phaseDeg" to p.phaseDeg)
    internal fun readProcessing(o: JSONObject) = Processing(o.getInt("length"), readChannel(o.getJSONObject("left")), readChannel(o.getJSONObject("right")), o.getDouble("delayUs"), o.getDouble("phaseDeg")).also { it.validate() }
    fun encode(s: Session): String {
        val out=java.io.StringWriter()
        write(s,out)
        return out.toString()
    }
    fun write(s: Session, out: java.io.Writer) {
        s.validate()
        // Histories dominate the session size. Materialize only one candidate at a time.
        val root=sessionObject(s)
        out.write("{")
        val keys=root.keys()
        while(keys.hasNext()) {
            val key=keys.next()
            out.write(JSONObject.quote(key));out.write(":");CompactJson.write(root.get(key),out);out.write(",")
        }
        out.write("\"experiments\":")
        ExperimentalRepository.writeLibrary(s.experiments,out)
        out.write("}")
    }
    private fun sessionObject(s: Session): JSONObject {
        val src = s.source
        return obj("type" to "speakerarray-session", "schemaVersion" to 5, "sampleRateHz" to SAMPLE_RATE,
            "autoFir" to CalibrationRepository.settings(s.autoFir), "calibration" to s.calibration?.let {CalibrationRepository.profile(it)},
            "adjustmentMethod" to s.adjustmentMethod.name,"autoToneHz" to s.autoToneHz,"toneProfile" to s.toneProfile?.let {tone(it)},
            "correctionMode" to s.correctionMode.name, "simple" to simple(s.simple),
            "correctionA" to s.correctionA?.let {snapshot(it)}, "correctionB" to s.correctionB?.let {snapshot(it)},
            "externalLayouts" to JSONObject().apply {s.externalLayouts.forEach {(k,v)->put(k,obj("spacingMm" to v.spacingMm,"reversed" to v.reversed))}},
            "processing" to processing(s.processing), "masterDb" to s.masterDb, "swapped" to s.swapped,
            "reverseLandscape" to s.reverseLandscape, "orientationChosen" to s.orientationChosen,
            "spacingMm" to s.spacingMm, "soundSpeed" to s.soundSpeed, "evaluationHz" to s.evaluationHz,
            "a" to s.a?.let { processing(it) }, "b" to s.b?.let { processing(it) },
            "source" to obj("kind" to src.kind.name, "frequency" to src.frequency, "bandLimit" to src.bandLimit,
                "low" to src.low, "high" to src.high, "sweepSeconds" to src.sweepSeconds, "logarithmic" to src.logarithmic,
                "loop" to src.loop, "wavUri" to src.wavUri, "mono" to src.mono.name, "loopFade" to src.loopFade))
    }
    private fun header(o: JSONObject, type: String) {
        require(o.getString("type") == type) { "ファイル種別が違います（$type が必要です）" }
        require(o.getDouble("schemaVersion") in (if(type=="speakerarray-session") listOf(1.0,2.0,3.0,4.0,5.0) else listOf(1.0))) { "未対応のschemaVersionです" }
        require(o.getDouble("sampleRateHz") == SAMPLE_RATE.toDouble()) { "48 kHzの設定のみ対応します。FIRを再設計してください" }
    }
    fun decode(text: String): Session {
        require(text.length <= MAX_SESSION_CHARS) { "設定ファイルが大きすぎます" }
        val o = JSONObject(text); header(o, "speakerarray-session")
        val v = o.getJSONObject("source")
        return Session(readProcessing(o.getJSONObject("processing")),
            SourceSettings(SourceKind.valueOf(v.getString("kind")), v.getDouble("frequency"), v.getBoolean("bandLimit"),
                v.getDouble("low"), v.getDouble("high"), v.getDouble("sweepSeconds"), v.getBoolean("logarithmic"),
                v.getBoolean("loop"), v.getString("wavUri"), MonoMode.valueOf(v.getString("mono")), v.getBoolean("loopFade")),
            o.getDouble("masterDb"), o.getBoolean("swapped"), o.getBoolean("reverseLandscape"), o.getBoolean("orientationChosen"),
            o.getDouble("spacingMm"), o.getDouble("soundSpeed"), o.getDouble("evaluationHz"),
            if (o.isNull("a")) null else readProcessing(o.getJSONObject("a")), if (o.isNull("b")) null else readProcessing(o.getJSONObject("b")),
            o.optJSONObject("autoFir")?.let {CalibrationRepository.readSettings(it)} ?: AutoFirSettings(),
            o.optJSONObject("calibration")?.let {CalibrationRepository.readProfile(it)}).let {legacy ->
                if(o.getInt("schemaVersion")<3) legacy else legacy.copy(
                    adjustmentMethod=AdjustmentMethod.valueOf(o.optString("adjustmentMethod","FIR")),autoToneHz=o.optDouble("autoToneHz",legacy.source.frequency),toneProfile=o.optJSONObject("toneProfile")?.let {readTone(it)},
                    correctionMode=CorrectionMode.valueOf(o.getString("correctionMode")),simple=readSimple(o.getJSONObject("simple")),
                    correctionA=o.optJSONObject("correctionA")?.let {readSnapshot(it)},correctionB=o.optJSONObject("correctionB")?.let {readSnapshot(it)},
                    externalLayouts=o.getJSONObject("externalLayouts").let {layouts->layouts.keys().asSequence().associateWith {k->
                        val v=layouts.getJSONObject(k);SpeakerLayout(v.getDouble("spacingMm"),v.getBoolean("reversed"))}})
            }.let {it.copy(experiments=o.optJSONObject("experiments")?.let(ExperimentalRepository::readLibrary) ?: ExperimentalLibrary())}.also { it.validate() }
    }
    private fun simple(s: SimpleCorrection)=obj("left" to channel(s.left),"right" to channel(s.right),
        "sinePhaseDeg" to s.sinePhaseDeg,"sineDelayUs" to s.sineDelayUs,"delayUs" to s.delayUs,"referenceHz" to s.referenceHz)
    private fun readSimple(o: JSONObject)=SimpleCorrection(readChannel(o.getJSONObject("left")),readChannel(o.getJSONObject("right")),
        o.getDouble("sinePhaseDeg"),o.getDouble("sineDelayUs"),o.getDouble("delayUs"),o.getDouble("referenceHz"))
    private fun snapshot(s: CorrectionSnapshot): JSONObject = obj("mode" to s.mode.name,"fir" to processing(s.fir),"simple" to simple(s.simple),
        "toneProfile" to s.toneProfile?.let {tone(it)},
        "source" to JSONObject(encode(Session(source=s.source))).getJSONObject("source"),"calibration" to s.calibration?.let {CalibrationRepository.profile(it)})
    private fun readSnapshot(o: JSONObject): CorrectionSnapshot {
        val base=JSONObject(encode(Session())).put("source",o.getJSONObject("source"))
        return CorrectionSnapshot(CorrectionMode.valueOf(o.getString("mode")),readProcessing(o.getJSONObject("fir")),readSimple(o.getJSONObject("simple")),
            decode(base.toString()).source,o.optJSONObject("calibration")?.let {CalibrationRepository.readProfile(it)},o.optJSONObject("toneProfile")?.let {readTone(it)})
    }
    private fun tone(p: ToneProfile)=obj("simple" to simple(p.simple),"frequency" to p.frequency,"masterDb" to p.masterDb,
        "swapped" to p.swapped,"settings" to CalibrationRepository.settings(p.settings),"volume" to p.volume,"mode" to p.mode,
        "layout" to p.layout?.let {obj("spacingMm" to it.spacingMm,"reversed" to it.reversed)},"reverseLandscape" to p.reverseLandscape,
        "backup" to JSONObject(p.backup),"result" to JSONObject(p.result))
    private fun readTone(o: JSONObject)=ToneProfile(readSimple(o.getJSONObject("simple")),o.getDouble("frequency"),o.getDouble("masterDb"),
        o.getBoolean("swapped"),CalibrationRepository.readSettings(o.getJSONObject("settings")),o.getInt("volume"),o.getInt("mode"),
        o.optJSONObject("layout")?.let {SpeakerLayout(it.getDouble("spacingMm"),it.getBoolean("reversed"))},o.getBoolean("reverseLandscape"),
        o.getJSONObject("backup").toString(),o.getJSONObject("result").toString())
    fun exportFir(spec: FirSpec, length: Int): String = obj("type" to "speakerarray-fir", "schemaVersion" to 1,
        "sampleRateHz" to SAMPLE_RATE, "coefficients" to numbers(FirDesigner.design(spec, length).toList()), "design" to firObject(spec)).toString(2)
    fun importFir(text: String, length: Int, csvRate: Int?): FirSpec {
        require(text.length <= 2_000_000) { "FIRファイルが大きすぎます" }
        val values = if (text.trimStart().startsWith("{")) {
            val o = JSONObject(text); header(o, "speakerarray-fir"); o.getJSONArray("coefficients").doubles()
        } else {
            require(csvRate == SAMPLE_RATE) { "CSVの係数設計レートに48000 Hzを指定してください" }
            val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (lines.firstOrNull()?.lowercase() == "coefficient") lines.removeAt(0)
            require(lines.none { it.contains(',') || it.contains(';') }) { "CSVは係数1列、任意ヘッダー coefficient です" }
            lines.map { it.toDouble() }
        }
        return FirSpec(mode=FirMode.COEFFICIENTS, coefficients=values).also { FirDesigner.validate(it, length) }
    }
    fun pasteCoefficients(text: String, length: Int): FirSpec {
        val values = text.trim().removePrefix("[").removeSuffix("]").split(Regex("[,;\\s]+" )).filter { it.isNotEmpty() }.map { it.toDouble() }
        return FirSpec(mode=FirMode.COEFFICIENTS, coefficients=values).also { FirDesigner.validate(it, length) }
    }
}
