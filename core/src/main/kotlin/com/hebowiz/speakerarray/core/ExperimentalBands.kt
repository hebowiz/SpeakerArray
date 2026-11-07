package com.hebowiz.speakerarray.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Fixed before measurement; shared by predictions and captured-power evaluation. */
object ExperimentalBands {
    const val RULE="[low, high), final [2000, 3400]; intersect requested band; bin Hz=48000*k/8192"
    data class Band(val low: Double,val high: Double,val bins: List<Int>) {
        fun json()=JSONObject().put("lowHz",low).put("highHz",high).put("boundaryRule",RULE)
            .put("bins",JSONArray(bins)).put("hz",JSONArray(bins.map {it*SAMPLE_RATE/8192.0}))
    }
    fun fixed(low: Double,high: Double): List<Band> = listOf(300.0 to 500.0,500.0 to 1000.0,1000.0 to 2000.0,2000.0 to 3400.0).map {(a,b)->
        Band(a,b,(0..4096).filter {val f=it*SAMPLE_RATE/8192.0;f in low..high && f>=a && (f<b || b==3400.0 && f<=b)})}
    fun measured(readings: List<ExperimentalReading>,band: Band): JSONObject {
        fun values(c: ExperimentalCondition)=readings.filter {it.condition==c}.flatMap {it.windows}.map {w->band.bins.sumOf {w.spectrum[it]}}
        val dark=values(ExperimentalCondition.DARK);val s=values(ExperimentalCondition.SINGLE)
        val b=values(ExperimentalCondition.BOTH);val c=values(ExperimentalCondition.CANDIDATE)
        val floor=dark.maxOrNull()?.coerceAtLeast(1e-20) ?: 1e-20
        fun spread(v: List<Double>)=powerDb(v.maxOrNull()!!.coerceAtLeast(1e-30)/v.minOrNull()!!.coerceAtLeast(1e-30))
        fun result(base: List<Double>): JSONObject {
            val valid=band.bins.isNotEmpty() && base.all {it>10*floor}
            return JSONObject().put("evaluable",valid).put("noiseLimited",c.any {it<=10*floor})
                .put("pointDb",if(valid) powerDb(base.average()/max(c.average(),floor)) else JSONObject.NULL)
                .put("lowerDb",if(valid) powerDb((base.minOrNull()!!-floor)/(c.maxOrNull()!!+floor)) else JSONObject.NULL)
        }
        val single=result(s);val both=result(b);val drift=max(spread(s),spread(b));val variation=spread(c)
        val noiseDrift=powerDb(dark.maxOrNull()!!.coerceAtLeast(1e-20)/dark.minOrNull()!!.coerceAtLeast(1e-20))
        val evaluable=single.getBoolean("evaluable") && both.getBoolean("evaluable")
        val stable=drift<=2 && (variation<=1.5 || c.any {it<=10*floor}) && noiseDrift<=3
        val increase=evaluable && (single.getDouble("pointDb")<0 || both.getDouble("pointDb")<0)
        return band.json().put("single",single).put("both",both).put("noiseFloorPower",floor).put("noiseDriftDb",noiseDrift)
            .put("baselineDriftDb",drift).put("candidateSpreadDb",variation).put("evaluable",evaluable).put("stable",stable)
            .put("singlePower",s.average()).put("bothPower",b.average()).put("candidatePower",c.average())
            .put("increaseObserved",increase)
            .put("status",if(!evaluable) "評価不能・基準がノイズ床付近または対象ビンなし" else if(!stable) "参考値・再現性不足" else if(increase) "増音を観測" else "比較結果あり")
            .put("windowPowers",JSONArray(readings.map {r->JSONObject().put("tag",r.tag).put("condition",r.condition.name)
                .put("powers",JSONArray(r.windows.map {w->band.bins.sumOf {w.spectrum[it]}}))}))
    }
}
