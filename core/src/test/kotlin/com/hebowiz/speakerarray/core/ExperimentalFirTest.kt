package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import kotlin.coroutines.*
import kotlin.math.*

class ExperimentalFirTest {
    private val settings=AutoFirSettings(lowHz=500.0,highHz=1000.0,taps=1025)
    private fun transfer(right: Boolean=false, bad: Boolean=false): Transfer {
        val re=DoubleArray(8192);val im=DoubleArray(8192)
        if(right) {re[31]=1.0;re[170]=-.12} else {re[20]=.8;re[120]=.18}
        Fft.transform(re,im)
        return Transfer(re.copyOf(4097),im.copyOf(4097),DoubleArray(4097) {if(bad && it%3==0) 10.0 else 60.0})
    }
    private fun input(bad: Boolean=false)=ExperimentalInput(settings,-24.0,List(2) {transfer(false,bad)},List(2) {transfer(true,bad)},"a".repeat(64),listOf("synthetic; PILOT_AMBIGUOUS"),"{\"reliable\":false}","REJECTED")
    private fun candidate()=ExperimentalFir.generate(input(),nonce="test")
    private fun diagnostic(): JSONObject {
        val a=JSONArray()
        for(i in 0..3) a.put(JSONObject().put("tag",if(i%2==0) "L" else "R").put("pilotBefore",i*200000L).put("signalFrame",i*200000L+24000).put("pilotAfter",i*200000L+170000).put("signalFrames",144000))
        val pilot=JSONArray((0..7).map {JSONObject().put("correlation",.5)})
        val t=JSONObject().put("method",SyncPolicy.VERSION).put("analyzable",true).put("reliable",false).put("offset48k",13000.0)
            .put("ratio",1.0).put("clockPpm",0.0).put("residual48k",1.2).put("rmsResidual48k",.7)
            .put("pilots",pilot).put("excluded",JSONArray()).put("findings",JSONArray().put(JSONObject().put("code","PILOT_AMBIGUOUS")))
        return JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",2).put("frequencyStepHz",48000.0/8192)
            .put("settings",CalibrationRepository.settings(settings)).put("masterDb",-24.0).put("clips",0).put("measurementOrder",a)
            .put("initialTiming",t).put("stage","REJECTED").put("reasonCode","BAND_QUALITY")
            .put("initialTransfers",JSONArray((input(true).left+input(true).right).map {v->JSONObject().put("real",jsonNumbers(v.re))
                .put("imaginary",jsonNumbers(v.im)).put("snrDb",jsonNumbers(v.snrDb)).put("recordedPower",jsonNumbers(v.recordedPower))}))
    }
    private fun <T> immediate(block: suspend ()->T): T {
        var result: Result<T>?=null
        block.startCoroutine(object: Continuation<T> {override val context=EmptyCoroutineContext;override fun resumeWith(r: Result<T>) {result=r}})
        return requireNotNull(result).getOrThrow()
    }
    @Test fun ambiguousLowCoverageCanDesignWithoutChangingFormalPolicy() {
        val d=diagnostic();val source=d.toString();val data=ExperimentalFir.readDiagnostic(source)
        val c=ExperimentalFir.generate(data,nonce="first")
        assertTrue(c.error,c.playable);assertFalse(JSONObject(c.report).getBoolean("formalAccepted"))
        assertFalse(JSONObject(c.report).getJSONObject("timing").getBoolean("reliable"))
        assertTrue(JSONObject(c.report).getInt("designBinCount")>=20)
        assertTrue(JSONObject(c.report).getDouble("trialCoverage")<.8)
        assertFalse(TransferEstimator.assess(data.left,data.right,settings,QualityPolicy.TRIAL).eligible)
        assertThrows(IllegalArgumentException::class.java) {TransferEstimator.pair(data.left,data.right,settings)}
        assertEquals(source,d.toString())
        val another=ExperimentalFir.generate(data,nonce="second")
        assertNotEquals(c.id,another.id);assertEquals(c.coefficientHash,another.coefficientHash)
    }
    @Test fun malformedOrForbiddenDiagnosticsNeverReachDesign() {
        for(code in listOf("TIME_MODEL_UNRESOLVED","TIME_AXIS_DISCONTINUITY","CLOCK_RANGE","PILOT_UNIDENTIFIED","PCM_DISCONTINUITY")) {
            val d=diagnostic();d.getJSONObject("initialTiming").getJSONArray("findings").put(JSONObject().put("code",code))
            assertThrows(IllegalArgumentException::class.java) {ExperimentalFir.readDiagnostic(d.toString())}
        }
        for(edit in listOf<(JSONObject)->Unit>(
            {it.put("clips",1)}, {it.put("frequencyStepHz",1.0)}, {it.remove("initialTransfers")},
            {it.put("reasonCode","ROUTE_OR_MODE")}, {it.getJSONArray("measurementOrder").getJSONObject(0).put("tag","R")},
            {it.getJSONArray("initialTransfers").getJSONObject(0).getJSONArray("real").put(100,JSONObject.NULL)},
            {it.getJSONObject("initialTiming").put("ratio",1.1)},
            {it.getJSONArray("initialTransfers").getJSONObject(0).put("snrDb",JSONArray(listOf(30.0)))})) {
            val d=diagnostic();edit(d);assertTrue(runCatching {ExperimentalFir.readDiagnostic(d.toString())}.isFailure)
        }
    }
    @Test fun designFailuresAreSavedAndNeverGainPlayableProcessing() {
        val src=input();val sparse=transfer().copy(snrDb=DoubleArray(4097) {if(it in 90..100) 60.0 else -120.0})
        val few=ExperimentalFir.generate(src.copy(left=listOf(sparse,sparse)),nonce="sparse")
        assertFalse(few.playable);assertTrue(few.error!!.contains("20未満"));assertNull(few.processing)
        val peak=ExperimentalFir.generate(src.copy(masterDb=0.0),nonce="peak");assertFalse(peak.playable)
        val loud=transfer().let {v->v.copy(re=DoubleArray(4097) {v.re[it]*10},im=DoubleArray(4097) {v.im[it]*10})}
        val boost=ExperimentalFir.generate(src.copy(left=listOf(loud,loud)),nonce="boost");assertFalse(boost.playable);assertTrue(boost.error!!.contains("ブースト"))
        val broken=src.copy(left=List(2) {Transfer(DoubleArray(4097) {Double.MAX_VALUE},DoubleArray(4097),DoubleArray(4097) {60.0})})
        assertFalse(ExperimentalFir.generate(broken,nonce="convergence").playable)
        val encoded=ExperimentalRepository.export(few);assertEquals(few,ExperimentalRepository.import(encoded))
    }
    @Test fun crossPredictionUsesTrainingOnlyAndKeepsAllLowSnrEvaluationBins() {
        val src=input();val c=ExperimentalFir.generate(src,"repeat1-to-2",nonce="cross")
        val changed=src.copy(left=listOf(src.left[0],src.left[1].copy(re=DoubleArray(4097) {1.0},snrDb=DoubleArray(4097) {-20.0})))
        val second=ExperimentalFir.generate(changed,"repeat1-to-2",nonce="cross2")
        assertEquals(c.processing,second.processing);assertEquals(c.weights,second.weights)
        val p=JSONObject(second.report).getJSONArray("predictions").getJSONObject(0)
        assertEquals(85,p.getJSONObject("fullBand").getInt("bins"));assertEquals(85,p.getJSONObject("fullBand").getInt("lowSnrBins"))
        assertFalse(p.getJSONObject("fullBand").isNull("singleDb"))
    }
    @Test fun libraryRoundTripAndOldSessionsKeepProfilesAndUndo() {
        val c=candidate();val base=Session(processing=Processing(phaseDeg=70.0),simple=SimpleCorrection(sinePhaseDeg=-90.0))
        val s=base.copy(experiments=ExperimentalLibrary(listOf(c),c.id,c.id,SessionRepository.encode(base)))
        val restored=SessionRepository.decode(SessionRepository.encode(s));assertEquals(c.processing,restored.experiments.current()!!.processing)
        assertEquals(base,SessionRepository.decode(restored.experiments.undo!!))
        val old=JSONObject(SessionRepository.encode(base)).put("schemaVersion",4).apply {remove("experiments")}
        assertEquals(base,SessionRepository.decode(old.toString()))
        val tampered=JSONObject(ExperimentalRepository.export(c));tampered.getJSONObject("candidate").put("coefficientHash","bad")
        assertTrue(runCatching {ExperimentalRepository.import(tampered.toString())}.isFailure)
    }
    /** All readings originate in actual SourceEngine + DspEngine PCM, then two FIR acoustic paths. */
    private class PcmMeter(val c: ExperimentalCandidate, val plan: ExperimentalPlan, val identical: Boolean=false,
        val delaySeconds: Double=0.0, val clockPpm: Double=0.0, val noise: Double=1e-6,
        val drift: Boolean=false) : ExperimentalMeter {
        override val coefficientHash get()=processingHash(c.processing!!)
        private val source=SourceEngine(SourceSettings(kind=SourceKind.WHITE,bandLimit=false),periodic=plan.noise())
        private val dsp=DspEngine(PreparedProcessing.create(ExperimentalComparison.processing(c,ExperimentalCondition.DARK)))
        private val l=DoubleArray(BLOCK);private val r=DoubleArray(BLOCK);private val out=FloatArray(BLOCK*2)
        private val left=DoubleArray((delaySeconds*SAMPLE_RATE).toInt()+512);private val right=DoubleArray(left.size)
        private val random=java.util.Random(51);private var index=0;var frames=0L;var calls=0
        private fun block(dest: MutableList<Double>) {
            source.render(l,r,750.0,0.0);dsp.process(l,r,out,c.masterDb,false);assertEquals(0,dsp.clips)
            for(i in 0 until BLOCK) {
                left[index]=out[i*2].toDouble();right[index]=out[i*2+1].toDouble()
                fun at(a: DoubleArray,offset: Int)=a[(index-offset-(delaySeconds*SAMPLE_RATE).toInt()+2*a.size)%a.size]
                var v=at(left,20)*.8+at(left,120)*.18
                v+=if(identical) at(right,20)*.8+at(right,120)*.18 else at(right,31)-at(right,170)*.12
                v*=if(drift && calls>=8) 1.7 else 1.0
                dest+=v+noise*(random.nextDouble()*2-1)
                index=(index+1)%left.size;frames++
            }
        }
        override suspend fun measure(tag: String,condition: ExperimentalCondition): ExperimentalReading {
            calls++;val changed=frames;dsp.submit(PreparedProcessing.create(ExperimentalComparison.processing(c,condition),calls.toLong()))
            val scratch=mutableListOf<Double>();val wait=max(plan.minimumWait,delaySeconds+1)
            repeat(ceil(wait*SAMPLE_RATE/BLOCK).toInt()) {scratch.clear();block(scratch)}
            val start=frames;val values=mutableListOf<BandWindow>()
            repeat(3) {
                val pcm=mutableListOf<Double>();repeat(ceil((plan.windowSeconds*SAMPLE_RATE+64)/BLOCK).toInt()) {block(pcm)}
                val samples=pcm.toDoubleArray()
                val data=if(clockPpm==0.0) pcm.take((plan.windowSeconds*SAMPLE_RATE).toInt()).toDoubleArray() else
                    DoubleArray((plan.windowSeconds*SAMPLE_RATE).toInt()) {i->val p=17+i*(1+clockPpm/1e6);MeasurementSync.sample(samples,p)}
                values+=ExperimentalPower.estimate(data,SAMPLE_RATE,plan)
            }
            return ExperimentalReading(tag,condition,values,calls.toLong(),changed+1,wait,changed+1,start,frames)
        }
    }
    @Test fun actualPcmReflectionComparisonMatchesFiniteFirPredictionAndNeverRedesigns() {
        val c=candidate();val plan=ExperimentalPlan.forSettings(settings);val designs=AutoFirDesigner.designInvocations.get()
        val result=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan))}
        assertTrue(result.toString(),result.getBoolean("evaluable"));assertTrue(result.toString(),result.getBoolean("stable"))
        assertTrue(result.getJSONObject("single").getDouble("lowerDb")>3)
        val predicted=JSONObject(c.report).getJSONArray("predictions").getJSONObject(0).getJSONObject("fullBand").getDouble("singleDb")
        assertEquals(predicted,result.getJSONObject("single").getDouble("pointDb"),1.0)
        assertEquals(designs,AutoFirDesigner.designInvocations.get());assertEquals(c.coefficientHash,result.getString("coefficientHashAfter"))
        assertFalse(result.getBoolean("formalAccepted"));assertEquals(3,result.getJSONArray("rounds").length())
    }
    @Test fun uncorrectedAndMutedCorrectionDoNotInventCancellationAndWrongPhaseKeepsIncrease() {
        val base=candidate();val both=ExperimentalComparison.processing(base,ExperimentalCondition.BOTH)
        val plan=ExperimentalPlan.forSettings(settings)
        for(mute in listOf(false,true)) {
            val p=if(mute) both.copy(right=both.right.copy(muted=true)) else both
            val c=base.copy(processing=p,id=if(mute) "muted" else "wrong-phase")
            val result=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan))}
            val value=result.getJSONObject("single").getDouble("pointDb")
            if(mute) assertEquals(0.0,value,.2) else {assertTrue(value<0);assertEquals("INCREASE",result.getString("outcome"))}
            assertTrue(result.getJSONObject("single").getDouble("lowerDb")<3)
        }
    }
    @Test fun partialBandDoesNotMasqueradeAsFullBandAndNoiseFloorIsBounded() {
        val src=input();val weights=TransferEstimator.assess(src.left,src.right,settings,QualityPolicy.TRIAL).pair.weights
        for(k in weights.indices) if(k*48000.0/8192>750) weights[k]=0.0
        val pair=TransferEstimator.assess(src.left,src.right,settings,QualityPolicy.TRIAL).pair.copy(weights=weights)
        val p=AutoFirDesigner.design(pair,settings,-24.0).processing;val c=candidate().copy(processing=p,weights=weights.toList())
        val plan=ExperimentalPlan.forSettings(settings);val r=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan))}
        assertTrue(r.getJSONObject("designAuxiliary").getDouble("singleDb")>r.getJSONObject("single").getDouble("pointDb"))
        val both=ExperimentalComparison.processing(c,ExperimentalCondition.BOTH)
        val anti=both.copy(right=both.right.copy(fir=both.right.fir.copy(coefficients=both.right.fir.coefficients.map {-it})))
        val exact=c.copy(processing=anti)
        val limited=immediate {ExperimentalComparison(exact,plan).run(PcmMeter(exact,plan,identical=true,noise=1e-4))}
        assertTrue(limited.getBoolean("noiseLimited"));assertTrue(limited.getJSONObject("single").getDouble("lowerDb").isFinite())
        val noisy=immediate {ExperimentalComparison(exact,plan).run(PcmMeter(exact,plan,identical=true,noise=.1))}
        assertFalse(noisy.getBoolean("evaluable"));assertTrue(noisy.getJSONObject("single").isNull("pointDb"))
    }
    @Test fun delayClockAndLevelDriftAreNotMistakenForIndependentSuccess() {
        val c=candidate();val plan=ExperimentalPlan.forSettings(settings)
        val delayed=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan,delaySeconds=3.217,clockPpm=200.0))}
        assertTrue(delayed.getJSONObject("single").getDouble("lowerDb")>3)
        val unstable=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan,drift=true))}
        assertFalse(unstable.getBoolean("stable"));assertTrue(unstable.getString("status").contains("再現性不足"))
    }
    @Test fun staleCaptureHashChangesAndUnsettledArrivalAreRejected() {
        val c=candidate();val plan=ExperimentalPlan.forSettings(settings)
        val real=PcmMeter(c,plan)
        val stale=object: ExperimentalMeter {
            override val coefficientHash=c.coefficientHash!!
            override suspend fun measure(tag: String,condition: ExperimentalCondition)=real.measure(tag,condition).copy(captureFrom=0)
        }
        assertTrue(runCatching {immediate {ExperimentalComparison(c,plan).run(stale)}}.isFailure)
        val changed=object: ExperimentalMeter {
            var calls=0;override val coefficientHash get()=if(calls==0) c.coefficientHash!! else "changed"
            override suspend fun measure(tag: String,condition: ExperimentalCondition): ExperimentalReading {calls++;return real.measure(tag,condition)}
        }
        assertTrue(runCatching {immediate {ExperimentalComparison(c,plan).run(changed)}}.isFailure)
        val dark=BandWindow(1e-12,List(4097) {0.0},0.0);val signal=dark.copy(power=1e-5)
        assertFalse(ExperimentalSettling.ready(List(3) {dark},2.0,2.0,1e-12,true))
        assertFalse(ExperimentalSettling.ready(List(3) {signal},1.9,2.0,1e-12,true))
        assertFalse(ExperimentalSettling.ready(List(3) {signal},2.0,2.0,1e-12,false))
        assertTrue(ExperimentalSettling.ready(List(3) {signal},4.0,2.0,1e-12,true))
        assertFalse(ExperimentalSettling.ready(listOf(signal,signal,signal.copy(power=1e-6)),4.0,2.0,1e-12,true))
    }
    @Test fun fullLegacyHistoryCanArchiveAndPruneOnlySavedSelectionWithoutChangingCandidate() {
        val base=candidate()
        val old=base.copy(comparisons=listOf("ABORTED","CANCELLED","COMPLETED").map {stage->
            JSONObject().put("candidateId",base.id).put("coefficientHash",base.coefficientHash).put("formalAccepted",false).put("stage",stage).toString()})
        val c=ExperimentalRepository.import(ExperimentalRepository.export(old))
        assertEquals(3,c.comparisons.size);assertEquals(3,c.comparisons.map(ExperimentalRuns::id).distinct().size)
        assertTrue(c.comparisons.all {JSONObject(it).isNull("startedAtEpochMs")})
        val archive=ExperimentalArchive.prepare(c);val selected=setOf(ExperimentalRuns.id(c.comparisons[1]))
        assertThrows(IllegalArgumentException::class.java) {archive.remove(c,selected)}
        assertThrows(IllegalArgumentException::class.java) {archive.completed(false).remove(c,selected)}
        val saved=archive.completed(true);val next=saved.remove(c,selected)
        assertEquals(c.copy(comparisons=listOf(c.comparisons[0],c.comparisons[2])),next)
        assertEquals(c,ExperimentalRepository.import(saved.payload))
        val restored=SessionRepository.decode(SessionRepository.encode(Session(experiments=ExperimentalLibrary(listOf(next),next.id))))
        val newer=ExperimentalRuns.start(restored.experiments.current()!!)
        assertFalse(c.comparisons.map(ExperimentalRuns::id).contains(newer.getString("runId")))
        assertEquals(1000,(1..1000).map {ExperimentalRuns.start(next).getString("runId")}.distinct().size)
        val changed=c.copy(comparisons=c.comparisons.map {JSONObject(it).put("status","changed").toString()})
        assertThrows(IllegalArgumentException::class.java) {saved.remove(changed,selected)}
    }
    @Test fun designCannotExpandOutsideExcitationAndChecksActualUpperFrequency() {
        val data=input()
        val count=AutoFirDesigner.designInvocations.get()
        val expanded=ExperimentalFir.generate(data,settings=data.settings.copy(lowHz=300.0,highHz=3400.0))
        assertFalse(expanded.playable);assertTrue(expanded.error!!.contains("未測定範囲"))
        assertEquals(count,AutoFirDesigner.designInvocations.get())
        val wide=data.copy(settings=settings.copy(lowHz=300.0,highHz=3400.0),timing="{\"residual48k\":4.0}")
        assertFalse(ExperimentalFir.generate(wide).playable)
        assertTrue(ExperimentalFir.generate(wide,settings=settings).playable)
    }
    @Test fun widebandActualPcmMatchesPredictionAndSubbandPowerSumsWithoutRedesign() {
        val wide=settings.copy(lowHz=300.0,highHz=3400.0)
        val c=ExperimentalFir.generate(input().copy(settings=wide));assertTrue(c.error,c.playable)
        val plan=ExperimentalPlan.forSettings(wide);val count=AutoFirDesigner.designInvocations.get()
        val result=immediate {ExperimentalComparison(c,plan).run(PcmMeter(c,plan))}
        val prediction=JSONObject(c.report).getJSONArray("predictions").getJSONObject(0)
        assertEquals(prediction.getJSONObject("fullBand").getDouble("singleDb"),result.getJSONObject("single").getDouble("pointDb"),1.0)
        val bands=ExperimentalBands.fixed(300.0,3400.0);val bins=bands.flatMap {it.bins}
        assertEquals(bins.size,bins.distinct().size);assertEquals(ExperimentalFir.requestBins(wide),bins)
        assertEquals(count,AutoFirDesigner.designInvocations.get());assertEquals(c.coefficientHash,result.getString("coefficientHashAfter"))
        val r=result.getJSONArray("subbands");val readings=result.getJSONArray("readings")
        for((condition,power) in listOf("SINGLE" to "singlePower","BOTH" to "bothPower","CANDIDATE" to "candidatePower")) {
            val full=(0 until readings.length()).map {readings.getJSONObject(it)}.filter {it.getString("condition")==condition}
                .flatMap {o->val w=o.getJSONArray("windows");(0 until w.length()).map {w.getJSONObject(it).getDouble("power")}}.average()
            assertEquals(full,(0 until r.length()).sumOf {r.getJSONObject(it).getDouble(power)},1e-14)
        }
        val p=prediction.getJSONArray("subbands")
        assertEquals(prediction.getJSONObject("fullBand").getDouble("candidatePower"),(0 until p.length()).sumOf {p.getJSONObject(it).getJSONObject("prediction").getDouble("candidatePower")},1e-12)
    }
    @Test fun fixedSubbandsKeepLocalIncreaseAndLocalNoiseFloorDespiteGoodOverallSnr() {
        val bands=ExperimentalBands.fixed(300.0,3400.0)
        val conditions=listOf(ExperimentalCondition.DARK)+List(6) {ExperimentalCondition.SINGLE}+List(6) {ExperimentalCondition.BOTH}+List(3) {ExperimentalCondition.CANDIDATE}+ExperimentalCondition.DARK
        val readings=conditions.mapIndexed {i,condition->
            val spectrum=MutableList(4097) {0.0}
            bands.forEachIndexed {j,band->val power=when(condition) {
                ExperimentalCondition.DARK->if(j==3) .01 else 1e-12
                ExperimentalCondition.SINGLE->if(j==3) .001 else 1.0
                ExperimentalCondition.BOTH->if(j==3) .001 else 2.0
                ExperimentalCondition.CANDIDATE->listOf(.1,.2,3.0,.001)[j]
            };band.bins.forEach {spectrum[it]=power/band.bins.size}}
            ExperimentalReading("$i",condition,List(3) {BandWindow(spectrum.sum(),spectrum,.1)},i.toLong(),i.toLong(),2.0,i.toLong(),i*100L,i*100L+50)
        }
        val low=ExperimentalBands.measured(readings,bands[0]);val increase=ExperimentalBands.measured(readings,bands[2]);val high=ExperimentalBands.measured(readings,bands[3])
        assertEquals(10.0,low.getJSONObject("single").getDouble("pointDb"),1e-8)
        assertTrue(increase.getJSONObject("single").getDouble("pointDb")<0)
        assertTrue(increase.getBoolean("increaseObserved"));assertEquals("増音を観測",increase.getString("status"))
        assertFalse(high.getBoolean("evaluable"));assertTrue(high.getJSONObject("single").isNull("pointDb"))
        val full=ExperimentalBands.measured(readings,ExperimentalBands.Band(300.0,3400.0,bands.flatMap {it.bins}))
        assertTrue(full.getBoolean("evaluable"))
        assertNotEquals((10.0+10*log10(5.0)+10*log10(1.0/3))/3,full.getJSONObject("single").getDouble("pointDb"),.1)
    }
}
