package com.hebowiz.speakerarray

import com.hebowiz.speakerarray.core.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

object MeasurementResultStore {
    fun encode(r: AutoMeasurementResult, generation: Long): String {
        val bins=r.pair.weights.indices.filter {it%8==0 && it>0}
        fun values(f: (Int)->Double)=JSONArray(bins.map {f(it).let {v->if(v.isFinite()) v else -120.0}})
        fun transfer(t: Transfer)=JSONObject().put("magnitudeDb",values {levelDb(t.magnitude(it))})
            .put("phaseDeg",values {atan2(t.im[it],t.re[it])*180/PI}).put("snrDb",values {t.snrDb[it]})
            .put("recordedPowerDb",values {10*log10(t.recordedPower[it].coerceAtLeast(1e-24))})
        return JSONObject().put("type","speakerarray-measurement").put("schemaVersion",1).put("generation",generation)
            .put("startedAtEpochMs",r.startedAt).put("recordedFrames",r.recordedFrames).put("outputRate",SAMPLE_RATE)
            .put("settings",CalibrationRepository.settings(r.settings)).put("requested",r.route.requested)
            .put("actual",r.route.actual).put("pcm",r.route.pcm).put("effects",r.route.effects).put("codec","unknown")
            .put("systemVolumeIndex",r.volume).put("audioMode",r.mode).put("frequencyHz",values {it*SAMPLE_RATE.toDouble()/TransferEstimator.SIZE})
            .put("weights",values {r.pair.weights[it]}).put("left",transfer(r.pair.left)).put("right",transfer(r.pair.right))
            .put("single",transfer(r.single)).put("both",transfer(r.both)).put("corrected",transfer(r.corrected))
            .put("relativePhaseDeg",values {wrapPhase((atan2(r.pair.right.im[it],r.pair.right.re[it])-atan2(r.pair.left.im[it],r.pair.left.re[it]))*180/PI)})
            .put("coverage",r.pair.coverage).put("repeatDb",r.pair.repeatDb).put("repeatPhaseDeg",r.pair.repeatPhase)
            .put("timing",JSONObject().put("offset48k",r.timing.offset).put("ratio",r.timing.ratio).put("ppm",r.timing.ppm)
                .put("residual48k",r.timing.residual).put("emittedFrames",JSONArray(r.timing.emitted)).put("capturedFrames48k",JSONArray(r.timing.captured)))
            .put("predictedResidualDb",r.solution.predictedDb).put("maxBoostDb",r.solution.maxBoostDb).put("peakBound",r.solution.peakBound)
            .put("verification",JSONArray(r.verification.map {JSONObject().put("singleReductionDb",it.reductionSingleDb)
                .put("bothReductionDb",it.reductionBothDb).put("bandReductionDb",JSONArray(it.bandReduction)).put("noiseLimited",it.noiseLimited)}))
            .put("leftFir",SessionRepository.firObject(r.solution.processing.left.fir)).put("rightFir",SessionRepository.firObject(r.solution.processing.right.fir)).toString(2)
    }
}
