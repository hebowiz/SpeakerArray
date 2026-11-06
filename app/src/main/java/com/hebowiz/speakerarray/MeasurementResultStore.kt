package com.hebowiz.speakerarray

import com.hebowiz.speakerarray.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Full resolution on disk, including failed stages. UI downsampling never changes evaluation. */
object MeasurementResultStore {
    fun failureCode(message: String): String = when {
        "クリップ" in message -> "CLIPPING"
        "PCM" in message || "フレーム" in message -> "PCM_DISCONTINUITY"
        "パイロット" in message -> "PILOT_UNIDENTIFIED"
        "権限" in message -> "PERMISSION"
        "フォーカス" in message -> "FOCUS_LOST"
        "音量" in message -> "VOLUME_CHANGED"
        "経路" in message || "デバイス" in message || "モード" in message -> "ROUTE_OR_MODE"
        "出力余裕" in message || "ブースト" in message -> "OUTPUT_BOUND"
        "収束" in message -> "DESIGN_CONVERGENCE"
        else -> "ACQUISITION_OR_DESIGN_FAILURE"
    }
    fun array(v: DoubleArray)=JSONArray(v.map {if(it.isFinite()) it else JSONObject.NULL})
    fun findings(v: List<Finding>)=JSONArray(v.map {JSONObject().put("code",it.code).put("verdict",it.verdict.name).put("message",it.message)})
    fun thresholds()=JSONObject().put("methodVersion",SyncPolicy.VERSION).put("snrDb",MeasurementLimits.SNR_DB)
        .put("repeatDb",MeasurementLimits.REPEAT_DB).put("repeatPhaseDeg",MeasurementLimits.REPEAT_DEGREES)
        .put("coverage",MeasurementLimits.MIN_COVERAGE).put("improvementDb",MeasurementLimits.IMPROVEMENT_DB)
        .put("subbandWorseningDb",MeasurementLimits.MAX_WORSENING_DB).put("legacySyncWarningSamples",MeasurementLimits.MAX_SYNC_RESIDUAL)
        .put("pilotCorrelation",SyncPolicy.MIN_CORRELATION).put("competitorRatio",SyncPolicy.MAX_COMPETITOR)
        .put("minimumPilots",SyncPolicy.MIN_PILOTS).put("coarseDiscontinuityRule","quarter-cycle at requested highHz; all pilots retained")
        .put("minimumPilotSpanSeconds",2).put("maxClockPpm",MeasurementLimits.MAX_CLOCK_PPM)
        .put("stepModelMinPointsPerSide",3).put("stepResidualFractionOfJump",0.2).put("stepResidualFractionOfLinearResidual",0.25)
        .put("correlationInterpolationFactor",16).put("suggestedBandMinimumBins",20).put("inputClipMagnitude",0.999)
    fun transfer(t: Transfer)=JSONObject().put("real",array(t.re)).put("imaginary",array(t.im))
        .put("snrDb",array(t.snrDb)).put("recordedPower",array(t.recordedPower))
    fun sync(s: SyncAssessment)=JSONObject().put("method",SyncPolicy.VERSION).put("offset48k",s.fit.offset)
        .put("clockPpm",s.fit.ppm).put("ratio",s.fit.ratio).put("residual48k",s.fit.residual)
        .put("rmsResidual48k",s.fit.rmsResidual).put("residualMicroseconds",s.fit.residual*1e6/SAMPLE_RATE)
        .put("analyzable",s.analyzable).put("reliable",s.reliable).put("used",JSONArray(s.used)).put("excluded",JSONArray(s.excluded))
        .put("findings",findings(s.findings)).put("pilots",JSONArray(s.pilots.mapIndexed {i,p ->JSONObject()
            .put("emittedFrame",s.fit.emitted[i]).put("detectedFrame48k",p.position)
            .put("predictedFrame48k",s.fit.offset+s.fit.ratio*s.fit.emitted[i]).put("signedResidual48k",s.fit.signedResiduals[i])
            .put("correlation",p.correlation).put("peak",p.peak).put("competitorFrame48k",p.competitorPosition)
            .put("competitorRatio",p.competitorRatio).put("peakNoiseWidthSamples",p.uncertaintySamples)
            .put("confidence",if(p.correlation>=SyncPolicy.MIN_CORRELATION && p.competitorRatio<=SyncPolicy.MAX_COMPETITOR) "MATCHED" else "AMBIGUOUS")}))
    fun pair(a: PairAssessment): JSONObject {
        val p=a.pair;val q=a.quality
        return JSONObject().put("eligible",a.eligible).put("coverage",p.coverage).put("left",transfer(p.left)).put("right",transfer(p.right))
            .put("weights",array(p.weights)).put("valid",JSONArray(q.valid.toList())).put("reasons",JSONArray(q.reasons.map {JSONArray(it)}))
            .put("leftRepeatDb",array(q.leftRepeatDb)).put("rightRepeatDb",array(q.rightRepeatDb))
            .put("leftRepeatPhaseDeg",array(q.leftRepeatPhase)).put("rightRepeatPhaseDeg",array(q.rightRepeatPhase))
            .put("findings",findings(a.findings)).put("suggestion",q.suggestion?.let {JSONObject().put("lowHz",it.lowHz).put("highHz",it.highHz)} ?: JSONObject.NULL)
    }
    fun verification(v: VerificationAssessment)=JSONObject().put("passed",v.passed).put("findings",findings(v.findings))
        .put("result",v.result?.let {JSONObject().put("singleReductionDb",it.reductionSingleDb).put("bothReductionDb",it.reductionBothDb)
            .put("bandReductionDb",JSONArray(it.bandReduction)).put("noiseLimited",it.noiseLimited)} ?: JSONObject.NULL)
    fun solution(s: FirSolution)=JSONObject().put("predictedResidualDb",s.predictedDb).put("maxBoostDb",s.maxBoostDb).put("peakBound",s.peakBound)
        .put("leftFir",SessionRepository.firObject(s.processing.left.fir)).put("rightFir",SessionRepository.firObject(s.processing.right.fir))
    fun encode(r: AutoMeasurementResult, generation: Long): String = JSONObject(r.diagnosticJson).put("generation",generation).toString(2)
}
