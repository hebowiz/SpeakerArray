package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*
import kotlin.math.*

class ToneAdjustmentTest {
    private val plan=TonePlan(743.7)
    private fun <T> immediate(block: suspend ()->T): T {
        var result: Result<T>?=null
        block.startCoroutine(object: Continuation<T> {
            override val context=EmptyCoroutineContext
            override fun resumeWith(r: Result<T>) {result=r}
        })
        return checkNotNull(result).getOrThrow()
    }
    private class Meter(val distort: (String,Double)->Double = {_,p->p}) : ToneMeter {
        var count=0
        val tags=mutableListOf<String>()
        override suspend fun measure(tag: String, condition: ToneCondition, point: TonePoint): ToneReading {
            count++;tags+=tag
            val a=Complex(1.0,0.0);val b=Complex.polar((73+point.phaseDegrees)*PI/180)*(0.63*dbGain(point.correctionDb))
            val p=when(condition) {
                ToneCondition.DARK -> 1e-10
                ToneCondition.SINGLE -> 0.01
                ToneCondition.BOTH -> (a+Complex.polar(73*PI/180)*0.63).magnitude.pow(2)*0.01
                ToneCondition.CANDIDATE -> (a+b).magnitude.pow(2)*0.01+1e-10
            }
            val measured=distort(tag,p)
            return ToneReading(tag,condition,point,List(3) {ToneWindow(measured*(1+(it-1)*0.005),1e-10,743.7,0.4)},
                count*10_000_000_000L,4.0,count*480000L,count*480000L,count*480000L+72000)
        }
    }
    @Test fun gridSearchFindsTwoPathMinimumAndUsesFreshReverseOrderVerification() {
        val meter=Meter();val search=ToneSearch(plan,-24.0)
        val result=immediate {search.run(meter)}
        assertTrue(result.reason,result.verified)
        assertEquals(107.0,result.point!!.phaseDegrees,3.0);assertEquals(-20*log10(.63),result.point!!.correctionDb,.51)
        assertEquals(2,result.verification.size)
        assertTrue(result.verification.all {it.singleLowerDb>15 && it.bothLowerDb>15})
        assertTrue(meter.tags.indexOf("verify0BOTH")<meter.tags.indexOf("verify0CANDIDATE"))
        assertTrue(meter.tags.indexOf("verify1CANDIDATE")<meter.tags.indexOf("verify1BOTH"))
        assertTrue(meter.count<=plan.maxMeasurements)
    }
    @Test fun nonintegerPeriodsClockErrorDcInterferenceAndLongFixedDelayDoNotFakePowerDrop() {
        for(delay in listOf(0.0,3.217)) for(ppm in listOf(-200.0,0.0,200.0)) {
            val actual=plan.frequency*(1+ppm/1e6)
            val pcm=DoubleArray(24000) {i->.17+.2*sin(2*PI*actual*(i/48000.0-delay))+.07*sin(2*PI*1277*i/48000)}
            val p=TonePower.estimate(pcm,48000,plan)
            assertEquals(.02,p.power,.0004)
            assertTrue(abs(p.detectedHz-plan.frequency)<=plan.bandwidthHz)
        }
    }
    @Test fun noCommonAttenuationOrZeroReferenceAndRightReferenceSignsAgree() {
        for(right in listOf(false,true)) for(gain in listOf(-6.0,0.0,6.0)) {
            val p=TonePoint(75.0,gain).simple(plan.copy(referenceRight=right))
            assertEquals(0.0,(if(right) p.right else p.left).gainDb,0.0)
            assertFalse(p.left.muted || p.right.muted)
            assertEquals(if(right) -gain else gain,p.ratioDb,0.0)
            assertEquals(if(right) -75.0 else 75.0,p.sinePhaseDeg,0.0)
        }
        assertFalse(TonePoint(0.0,6.0).safe(-3.0))
        assertFalse(TonePoint(0.0,6.5).safe(-24.0))
    }
    @Test fun settlingRejectsBufferedOldAudioSlowAgcAndUnfinishedStep() {
        fun windows(vararg powers: Double)=powers.map {ToneWindow(it,0.0,743.7,.5)}
        val steady=windows(.01,.01,.01)
        assertFalse(ToneSettling.ready(steady,2.5,4.0,1e-10,true))
        assertTrue(ToneSettling.ready(steady,4.0,4.0,1e-10,true))
        assertFalse(ToneSettling.ready(windows(.01,.005,.002),4.0,4.0,1e-10,true))
        assertFalse(ToneSettling.ready(steady,4.0,4.0,1e-10,false))
        assertFalse(ToneSettling.ready(windows(1e-10,1e-10,1e-10),4.0,4.0,1e-10,true))
        assertTrue(ToneSettling.ready(windows(1e-10,1e-10,1e-10),4.0,4.0,1e-10,false))
    }
    @Test fun driftAgcOrderDependenceAndNoiseFloorCannotPass() {
        for(distort in listOf<(String,Double)->Double>(
            {t,p->if(t.startsWith("reference")) p*.6 else p},
            {t,p->if(t=="verify1CANDIDATE") .01 else p},
            {t,p->if(t=="verify1BothAfter") p*1.5 else p},
            {t,p->if(t=="dark") .009 else p}
        )) {
            val outcome=runCatching {immediate {ToneSearch(plan,-24.0).run(Meter(distort))}}
            assertTrue(outcome.isFailure || !outcome.getOrThrow().verified)
        }
    }
    @Test fun staleFramesShortWaitClippingRouteAndPcmFailureNeverAccept() {
        for(fault in 0..4) {
            val underlying=Meter()
            val meter=object: ToneMeter {
                override suspend fun measure(tag: String, condition: ToneCondition, point: TonePoint): ToneReading {
                    if(fault==4) error("経路切断")
                    val r=underlying.measure(tag,condition,point)
                    return when(fault) {
                        0 -> r.copy(captureFrom=0,captureUntil=72000)
                        1 -> r.copy(waitedSeconds=.1)
                        2 -> r.copy(windows=r.windows.map {it.copy(peak=1.0)})
                        else -> r.copy(windows=r.windows.map {it.copy(power=Double.NaN)})
                    }
                }
            }
            assertTrue(runCatching {immediate {ToneSearch(plan,-24.0).run(meter)}}.isFailure)
        }
    }
    @Test fun separateValidationFailureDoesNotApplyAndSuccessfulSessionRoundTripsBothSlots() {
        val before=Session(processing=Processing(left=Channel(gainDb=-3.0)),correctionMode=CorrectionMode.FIR,autoToneHz=plan.frequency)
        val result=immediate {ToneSearch(plan,-24.0).run(Meter())}
        val profile=ToneProfile(result.point!!.simple(plan),plan.frequency,-24.0,false,before.autoFir,7,3,before.activeLayout,false,SessionRepository.encode(before),"{}")
        val after=ToneTransaction.accept(before,result,profile)
        assertEquals(before.processing,after.processing);assertEquals(before.correctionA,after.correctionA)
        assertEquals(CorrectionMode.SIMPLE,after.correctionMode)
        val decoded=SessionRepository.decode(SessionRepository.encode(after.copy(correctionA=after.correctionSnapshot())))
        assertEquals(after.simple,decoded.simple);assertEquals(after.processing,decoded.processing)
        assertEquals(after.simple,decoded.correctionA!!.toneProfile!!.simple)
        assertTrue(decoded.toneProfile!!.matches(decoded,7,3,true))
        assertFalse(decoded.toneProfile!!.matches(decoded.copy(source=decoded.source.copy(frequency=800.0)),7,3,true))
        assertFalse(decoded.toneProfile!!.matches(decoded,7,3,false))
        assertTrue(runCatching {ToneTransaction.accept(before,result.copy(verified=false),profile)}.isFailure)
        assertEquals(before,SessionRepository.decode(SessionRepository.encode(before)))
    }
    @Test fun conservativeReductionIncludesWorstWindowsInsteadOfOnlyMean() {
        val result=immediate {ToneSearch(plan,-24.0).run(Meter {tag,p->if(tag.startsWith("verify") && tag.endsWith("CANDIDATE")) .0051 else p})}
        assertFalse(result.verified)
        assertTrue(result.verification.any {it.singleLowerDb<3})
    }
    @Test fun versionThreeSessionKeepsBothSlotsAndSeedsOnlyNewToneFields() {
        val before=Session(source=SourceSettings(frequency=876.5),correctionMode=CorrectionMode.SIMPLE,
            processing=Processing(phaseDeg=60.0),simple=SimpleCorrection(sinePhaseDeg=-30.0))
        val json=org.json.JSONObject(SessionRepository.encode(before)).put("schemaVersion",3)
        for(key in listOf("adjustmentMethod","autoToneHz","toneProfile")) json.remove(key)
        val restored=SessionRepository.decode(json.toString())
        assertEquals(before.processing,restored.processing);assertEquals(before.simple,restored.simple)
        assertEquals(CorrectionMode.SIMPLE,restored.correctionMode)
        assertEquals(876.5,restored.autoToneHz,0.0);assertEquals(AdjustmentMethod.FIR,restored.adjustmentMethod)
    }
    private fun transfer(degrees: Double=0.0,gain: Double=1.0,power: Double=1.0)=Transfer(
        DoubleArray(4097) {gain*cos(degrees*PI/180)},DoubleArray(4097) {gain*sin(degrees*PI/180)},DoubleArray(4097) {60.0},DoubleArray(4097) {power})
    @Test fun strictFailureCanEnterTrialButTimingDiscontinuityStillStops() {
        val config=AutoFirSettings(lowHz=500.0,highHz=1000.0)
        val values=listOf(transfer(),transfer(15.0,dbGain(1.5)))
        assertFalse(TransferEstimator.assess(values,values,config,QualityPolicy.STRICT).eligible)
        assertTrue(TransferEstimator.assess(values,values,config,QualityPolicy.TRIAL).eligible)
        val emitted=(0..7).map {it*48000.0}
        val pilots=emitted.mapIndexed {i,t->PilotDetection(t+10000+(if(i>3) 100.0 else 0.0),.99,1.0,t+12000,.1,.1)}
        assertFalse(SyncPolicy.assess(emitted,pilots,1000.0).analyzable)
    }
    @Test fun firConservativeEnvelopeRejectsOptimisticPointEstimateAndLevelInstability() {
        val weights=DoubleArray(4097) {if(it in 86..170) 1.0 else 0.0}
        val stable=MeasurementQuality.conservative(List(4) {transfer()},List(2) {transfer(power=2.0)},List(2) {transfer(power=.25)},weights)
        assertTrue(stable.findings.toString(),stable.passed)
        val changed=MeasurementQuality.conservative(List(4) {transfer(power=if(it==3) .5 else 1.0)},List(2) {transfer(power=2.0)},List(2) {transfer(power=.4)},weights)
        assertFalse(changed.passed)
        assertTrue(changed.findings.any {it.code=="RECORDING_LEVEL_UNSTABLE"})
    }
}
