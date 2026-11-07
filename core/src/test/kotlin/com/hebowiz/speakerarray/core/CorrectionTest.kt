package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.math.*

class CorrectionTest {
    @Test fun resolvedBluetoothPolicyRejectsAllBtInputsAndUnknownButPreservesHfpSpeaker() {
        for(out in listOf(8,26,27,30)) {
            for(mic in listOf(15,3,11,12,22)) assertNull(RoutePolicy.reason(out,mic))
            for(mic in listOf(7,26,27,30,23,0,999)) assertThrows(IllegalArgumentException::class.java) {RoutePolicy.requireAllowed(out,mic)}
        }
        assertNull(RoutePolicy.reason(2,7));assertNull(RoutePolicy.reason(11,7))
        assertNotNull(RoutePolicy.reason(7,15))
        // Stored route selections stay intact; restoration does not substitute a microphone.
        val s=Session(autoFir=AutoFirSettings(output=EndpointKey(8,"receiver","a"),input=EndpointKey(26,"mic","b")))
        val restored=SessionRepository.decode(SessionRepository.encode(s))
        assertEquals(s.autoFir,restored.autoFir)
        assertNotNull(RoutePolicy.reason(restored.autoFir.output!!.type,restored.autoFir.input!!.type))
    }
    @Test fun firSimpleRoundTripAndJsonAbKeepBothSlotsWithoutStacking() {
        val fir=Processing(left=Channel(gainDb=-3.0,fir=FirSpec(FirMode.LPF,high=500.0)),right=Channel(inverted=true),delayUs=83.0,phaseDeg=75.0)
        val s=Session(processing=fir,simple=SimpleCorrection(right=Channel(gainDb=-6.0),sinePhaseDeg=-90.0,delayUs=135.0,referenceHz=700.0))
        val simple=s.copy(correctionMode=CorrectionMode.SIMPLE)
        assertEquals(FirMode.FLAT,simple.effectiveProcessing().left.fir.mode)
        assertEquals(0.0,simple.effectiveProcessing().left.gainDb,0.0)
        assertEquals(-6.0,simple.effectiveProcessing().right.gainDb,0.0)
        assertFalse(simple.effectiveProcessing().right.inverted)
        val ab=simple.copy(correctionA=s.correctionSnapshot(),correctionB=simple.correctionSnapshot())
        val decoded=SessionRepository.decode(SessionRepository.encode(ab))
        assertEquals(ab,decoded)
        assertEquals(fir,decoded.restore(decoded.correctionA!!).effectiveProcessing())
        assertEquals(simple.effectiveProcessing(),decoded.restore(decoded.correctionB!!).effectiveProcessing())
        assertEquals(fir,simple.copy(correctionMode=CorrectionMode.FIR).effectiveProcessing())
        assertTrue(ResponseAnalyzer.channel(fir,false,4000.0,false).magnitude<0.01)
        assertEquals(1.0,ResponseAnalyzer.channel(simple.effectiveProcessing(),false,4000.0,false).magnitude,1e-7)
    }
    @Test fun legacyMigrationPreservesAudibleProcessingAndSeedsIndependentSimple() {
        for(version in listOf(1,2)) {
            val old=Session(processing=Processing(left=Channel(gainDb=-9.0,enabled=false),right=Channel(inverted=true),phaseDeg=-90.0,delayUs=250.0))
            val json=JSONObject(SessionRepository.encode(old)).put("schemaVersion",version)
            for(k in listOf("simple","correctionMode","correctionA","correctionB","externalLayouts")) json.remove(k)
            val read=SessionRepository.decode(json.toString())
            assertEquals(CorrectionMode.FIR,read.correctionMode);assertEquals(old.processing,read.effectiveProcessing())
            assertEquals(-9.0,read.simple.left.gainDb,0.0);assertEquals(-90.0,read.simple.sinePhaseDeg,0.0)
            assertTrue(read.simple.right.inverted)
        }
    }
    @Test fun sourceConversionPreservesUnwrappedDelayAndPolarityAndRejectsOverflow() {
        val s=Session(correctionMode=CorrectionMode.SIMPLE,source=SourceSettings(frequency=1000.0),
            simple=SimpleCorrection(right=Channel(inverted=true),sinePhaseDeg=90.0,sineDelayUs=75.0))
        val noise=s.changeSource(s.source.copy(kind=SourceKind.WHITE))
        assertEquals(-175.0,noise.simple.delayUs,1e-10)
        assertEquals(s.simple.phaseAt(1000.0,true),noise.simple.phaseAt(1000.0,false),1e-8)
        val ref=noise.copy(simple=noise.simple.copy(referenceHz=4000.0))
        assertEquals(-175.0,ref.simple.delayUs,0.0)
        assertEquals(432.0,ref.simple.phaseAt(4000.0,false),1e-8)
        val back=ref.changeSource(ref.source.copy(kind=SourceKind.SINE))
        assertEquals(90.0,back.simple.sinePhaseDeg,0.0);assertEquals(75.0,back.simple.sineDelayUs,0.0)
        val bad=s.copy(source=s.source.copy(frequency=20.0),simple=s.simple.copy(sineDelayUs=10000.0,sinePhaseDeg=-180.0))
        assertThrows(IllegalArgumentException::class.java) {bad.changeSource(bad.source.copy(kind=SourceKind.WHITE))}
        assertEquals(10000.0,bad.simple.sineDelayUs,0.0)
    }
    @Test fun broadbandDelayFollowsFrequencyAndIsNotPolarity() {
        val p=SimpleCorrection(delayUs=250.0).processing(513,false)
        for(hz in listOf(250.0,1000.0,2000.0,4000.0)) {
            val l=ResponseAnalyzer.channel(p,false,hz,false);val r=ResponseAnalyzer.channel(p,true,hz,false)
            assertEquals(0.0,wrapPhase((r.phase-l.phase)*180/PI+360*hz*250/1e6),0.03)
        }
        assertEquals(-90.0,SimpleCorrection(delayUs=250.0).phaseAt(1000.0,false),0.0)
        assertEquals(180.0,SimpleCorrection(right=Channel(inverted=true)).phaseAt(1000.0,false),0.0)
    }
    private fun tone(p: Processing, swapped: Boolean): Pair<Double,Double> {
        val source=SourceEngine(SourceSettings(frequency=1000.0))
        val engine=DspEngine(PreparedProcessing.create(p,1,true))
        val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK);val out=FloatArray(BLOCK*2)
        val samples=ArrayList<Float>()
        repeat(60) {source.render(l,r,1000.0,0.0,true);engine.process(l,r,out,-20.0,swapped);if(it>=30) samples.addAll(out.toList())}
        val count=7200
        fun component(ch: Int): Complex {
            var re=0.0;var im=0.0
            for(i in 0 until count) {val v=samples[samples.size-count*2+i*2+ch].toDouble();val a=2*PI*1000*i/SAMPLE_RATE;re+=v*cos(a);im-=v*sin(a)}
            return Complex(re,im)
        }
        val a=component(0);val b=component(1)
        return b.magnitude/a.magnitude to wrapPhase((b.phase-a.phase)*180/PI)
    }
    @Test fun actualQuadratureDspHasCorrectRatioPhaseAndSwapWithoutDoubleApplication() {
        val p=Processing(right=Channel(gainDb=-6.0),phaseDeg=90.0,delayUs=125.0)
        val a=tone(p,false);val swapped=tone(p,true)
        assertEquals(dbGain(-6.0),a.first,1e-5);assertEquals(45.0,a.second,0.03)
        assertEquals(1/dbGain(-6.0),swapped.first,1e-5);assertEquals(-45.0,swapped.second,0.03)
        assertEquals(45.0,tone(p.copy(left=Channel(gainDb=-3.0)),false).second,0.03)
    }
    @Test fun wholePathCrossfadeSharesOscillatorHistoryAndConvergesToLastRequest() {
        val source=SourceEngine(SourceSettings(frequency=1000.0))
        val original=Processing();val engine=DspEngine(PreparedProcessing.create(original,0,true))
        val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK);val out=FloatArray(BLOCK*2)
        var previous=0.0;var largestStep=0.0
        repeat(100) {block->
            if(block in 30..60) engine.submit(PreparedProcessing.create(original.copy(phaseDeg=if(block%2==0) -90.0 else 90.0),block.toLong(),true))
            source.render(l,r,1000.0,0.0,true);engine.process(l,r,out,-20.0,false)
            for(i in 0 until BLOCK) {val v=out[i*2+1].toDouble();largestStep=max(largestStep,abs(v-previous));previous=v}
        }
        assertEquals(60L,engine.appliedGeneration);assertEquals(0L,engine.clips)
        assertTrue("crossfade sample step $largestStep",largestStep<0.015)
        assertEquals(original.length/2,FirDesigner.flat(original.length).indexOfFirst {it==1.0})
    }
    @Test fun failedMeasurementAndSoloNeverMutateCorrectionSlots() {
        val s=Session(correctionMode=CorrectionMode.SIMPLE,simple=SimpleCorrection(delayUs=123.0))
        val transaction=CalibrationTransaction(s,100)
        assertEquals(s,transaction.rollback())
        val muted=s.effectiveProcessing().withSolo(true)
        assertTrue(muted.left.muted);assertFalse(s.simple.left.muted)
        assertEquals(s,SessionRepository.decode(SessionRepository.encode(s)))
    }
    @Test fun successfulCalibrationOnlyUpdatesFirAndKeepsSimpleAndUndoBackup() {
        val original=Session(correctionMode=CorrectionMode.SIMPLE,simple=SimpleCorrection(sinePhaseDeg=-90.0,delayUs=451.0,referenceHz=500.0))
        val applied=Processing(right=Channel(inverted=true))
        val profile=CalibrationProfile(10,original.autoFir,original.masterDb,false,10,0,false,applied,SessionRepository.encode(original),"{}")
        val evidence=AcceptanceEvidence(true,true,true,true,true,true)
        val v=Verification(6.0,7.0,listOf(5.0),false)
        val accepted=CalibrationTransaction(original,10).accept(profile,List(4) {v},evidence)
        assertEquals(CorrectionMode.FIR,accepted.correctionMode);assertEquals(applied,accepted.processing)
        assertEquals(original.simple,accepted.simple)
        assertEquals(original,SessionRepository.decode(accepted.calibration!!.backup))
    }
    @Test fun fullDiagnosticInAbRoundTripsBeyondOldTwoMillionCharacterLimit() {
        val s=Session()
        val diagnostic=JSONObject().put("values",org.json.JSONArray(List(300000) {0.123456789})).toString()
        val profile=CalibrationProfile(1,s.autoFir,s.masterDb,false,10,0,false,s.processing,JSONObject(SessionRepository.encode(s)).toString(),diagnostic)
        val calibrated=s.copy(calibration=profile)
        val saved=calibrated.copy(correctionA=calibrated.correctionSnapshot())
        val text=SessionRepository.encode(saved)
        assertTrue(text.length>2_000_000)
        assertEquals(saved,SessionRepository.decode(text))
    }
    @Test fun measuredBadgeDoesNotLeakAndExternalLayoutIsSeparate() {
        val s=Session();val profile=CalibrationProfile(1,s.autoFir,s.masterDb,false,10,0,false,s.processing,SessionRepository.encode(s),"{}")
        assertTrue(profile.matches(s.copy(calibration=profile),10))
        assertFalse(profile.matches(s.copy(calibration=profile,correctionMode=CorrectionMode.SIMPLE),10))
        assertFalse(profile.matches(s.copy(processing=s.processing.copy(phaseDeg=90.0)),10))
        val bt=s.copy(autoFir=s.autoFir.copy(output=EndpointKey(8,"stereo","address")))
        assertArrayEquals(ResponseAnalyzer.pattern(s),ResponseAnalyzer.pattern(s.copy(reverseLandscape=true)),0.0)
        assertNull(bt.activeLayout);assertTrue(ResponseAnalyzer.pattern(bt).all {it.isNaN()})
        val external=bt.copy(externalLayouts=mapOf(bt.layoutKey to SpeakerLayout(500.0)))
        assertEquals(500.0,external.activeLayout!!.spacingMm,0.0);assertEquals(150.0,external.spacingMm,0.0)
        assertEquals(external,SessionRepository.decode(SessionRepository.encode(external)))
    }
}
