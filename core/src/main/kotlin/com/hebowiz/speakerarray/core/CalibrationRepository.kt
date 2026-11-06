package com.hebowiz.speakerarray.core

import org.json.JSONObject

data class CalibrationProfile(val generation: Long, val settings: AutoFirSettings, val masterDb: Double,
    val swapped: Boolean, val volumeIndex: Int, val mode: Int, val hfp: Boolean,
    val applied: Processing, val backup: String, val resultJson: String) {
    fun matches(s: Session, volume: Int) = s.masterDb==masterDb && s.swapped==swapped && volume==volumeIndex &&
        s.processing==applied && s.autoFir==settings
}

object CalibrationRepository {
    private fun endpoint(key: EndpointKey?): Any = key?.let {JSONObject().put("type",it.type).put("name",it.name).put("address",it.address)} ?: JSONObject.NULL
    private fun readEndpoint(o: JSONObject?): EndpointKey? = o?.let {EndpointKey(it.getInt("type"),it.getString("name"),it.getString("address"))}
    fun settings(s: AutoFirSettings): JSONObject = JSONObject().put("output",endpoint(s.output)).put("input",endpoint(s.input))
        .put("preset",s.preset).put("inputRate",s.inputRate).put("referenceRight",s.referenceRight)
        .put("lowHz",s.lowHz).put("highHz",s.highHz).put("seconds",s.seconds).put("tailSeconds",s.tailSeconds)
        .put("latencySeconds",s.latencySeconds).put("taps",s.taps).put("regularization",s.regularization)
        .put("maxBoostDb",s.maxBoostDb).put("signal",s.signal.name)
    fun readSettings(o: JSONObject): AutoFirSettings = AutoFirSettings(readEndpoint(o.optJSONObject("output")),readEndpoint(o.optJSONObject("input")),
        o.getInt("preset"),o.getInt("inputRate"),o.getBoolean("referenceRight"),o.getDouble("lowHz"),o.getDouble("highHz"),
        o.getDouble("seconds"),o.getDouble("tailSeconds"),o.getDouble("latencySeconds"),o.getInt("taps"),
        o.getDouble("regularization"),o.getDouble("maxBoostDb"),ProbeKind.valueOf(o.getString("signal"))).also {it.validate()}
    fun profile(p: CalibrationProfile): JSONObject = JSONObject().put("generation",p.generation).put("settings",settings(p.settings))
        .put("masterDb",p.masterDb).put("swapped",p.swapped).put("volumeIndex",p.volumeIndex).put("mode",p.mode).put("hfp",p.hfp)
        .put("applied",SessionRepository.processing(p.applied)).put("backup",JSONObject(p.backup)).put("result",JSONObject(p.resultJson))
    fun readProfile(o: JSONObject): CalibrationProfile = CalibrationProfile(o.getLong("generation"),readSettings(o.getJSONObject("settings")),
        o.getDouble("masterDb"),o.getBoolean("swapped"),o.getInt("volumeIndex"),o.getInt("mode"),o.getBoolean("hfp"),
        SessionRepository.readProcessing(o.getJSONObject("applied")),o.getJSONObject("backup").toString(),o.getJSONObject("result").toString())
}
