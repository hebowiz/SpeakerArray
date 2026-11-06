package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class PracticalAutoFirTest {
    private val config=AutoFirSettings(highHz=3400.0,seconds=1.0)
    private fun response(delay: Int=0, gain: Double=1.0): Transfer {
        val r=DoubleArray(4097) {gain*cos(-2*PI*it*delay/8192)}
        val i=DoubleArray(4097) {gain*sin(-2*PI*it*delay/8192)}
        return Transfer(r,i,DoubleArray(4097) {60.0})
    }
    private data class Synth(val pcm: DoubleArray,val emitted: List<Double>,val truePositions: List<Double>,val pilot: DoubleArray)
    private fun synth(noise: Double=0.002, interference: Double=0.0, jump: Double=0.0, movingReflection: Boolean=false): Synth {
        val pilot=KnownProbe.pilot(config)
        val n=powerOfTwo(pilot.size+300)
        val r=pilot.copyOf(n);val im=DoubleArray(n);Fft.transform(r,im)
        for(k in 0 until n) if(min(k,n-k)*48000.0/n !in 350.0..1800.0) {r[k]=0.0;im[k]=0.0}
        Fft.transform(r,im,true)
        val filtered=DoubleArray(pilot.size+200) {i->r[i]+if(i>=23) 0.45*r[i-23] else 0.0}
        val emitted=(0..7).map {30000.0+it*50003}
        val positions=emitted.mapIndexed {j,t->4000.37+t*1.00008+if(j>=4) jump else 0.0}
        val random=Random(54);val pcm=DoubleArray(430000) {noise*(random.nextDouble()*2-1)}
        for((j,pos) in positions.withIndex()) {
            val pad=DoubleArray(filtered.size+64);filtered.copyInto(pad,32)
            for(i in floor(pos).toInt() until ceil(pos+filtered.size*1.00008).toInt()) {
                val p=(i-pos)/1.00008+32
                if(p>=16 && p<pad.size-17) pcm[i]+=MeasurementSync.sample(pad,p)
            }
            // Colored acoustic interference exists only during the synchronization probe.
            for(i in filtered.indices) pcm[pos.toInt()+i]+=interference*sin(2*PI*700*i/48000+j*1.3)
            if(movingReflection && j==3) for(i in filtered.indices) pcm[pos.toInt()+i+180]+=filtered[i]*2.0
        }
        return Synth(pcm,emitted,positions,pilot)
    }
    private fun detect(s: Synth, common: Boolean=true): List<PilotDetection> {
        val first=MeasurementSync.detect(s.pcm,s.pilot,30000,55000)
        val template=if(common) DoubleArray(s.pilot.size) {MeasurementSync.sample(s.pcm,first.position+it)} else s.pilot
        return s.emitted.map {t->MeasurementSync.detect(s.pcm,template,t.toInt(),t.toInt()+25000)}
    }
    @Test fun actualBandLimitedPilotWithReflectionNoiseFractionalDelayAndClockUsesOneMap() {
        val s=synth();val detections=detect(s)
        val result=SyncPolicy.assess(s.emitted,detections,config.highHz)
        println("pilot waveform: ppm=${result.fit.ppm}, residual=${result.fit.residual}, correlation=${detections.minOf {it.correlation}}, competitor=${detections.maxOf {it.competitorRatio}}")
        assertTrue(result.findings.toString(),result.reliable)
        assertEquals(80.0,result.fit.ppm,1.5)
        // Absolute template bias is shared; relative timing remains unchanged.
        for(i in 1..7) assertEquals(s.truePositions[i]-s.truePositions[0],detections[i].position-detections[0].position,0.3)
        val raw=MeasurementSync.estimate(s.emitted,detect(s,false).map {it.position})
        println("ideal-template residual=${raw.residual}; common-template residual=${result.fit.residual}")
    }
    @Test fun stableClockWithDetectionScatterAboveLegacyLimitContinuesButNeedsQuality() {
        val s=synth(noise=0.08,interference=0.3)
        val detection=detect(s)
        val result=SyncPolicy.assess(s.emitted,detection,config.highHz)
        println("noisy waveform residual=${result.fit.residual}, confidence=${detection.minOf {it.correlation}}")
        assertTrue(result.analyzable)
        assertTrue(result.fit.residual>0.35)
        assertTrue(result.reliable)
        val a=response();val q=TransferEstimator.assess(listOf(a,a),listOf(a,a),config)
        assertTrue(q.eligible);assertTrue(AutoFirDesigner.design(q.pair,config,-24.0).predictedDb < -10)
        val bad=TransferEstimator.assess(listOf(a,response(3)),listOf(a,a),config)
        assertFalse(bad.eligible)
    }
    @Test fun waveformClockJumpAndMissingFramesCannotBeRemovedAsOutliers() {
        for(jump in listOf(48.0,-160.0)) {
            val s=synth(jump=jump);val result=SyncPolicy.assess(s.emitted,detect(s),config.highHz)
            assertFalse(result.findings.toString(),result.analyzable)
            assertTrue(result.excluded.isEmpty());assertEquals(8,result.used.size)
        }
        val s=synth();assertFalse(SyncPolicy.assess(s.emitted,detect(s),config.highHz,continuous=false).analyzable)
    }
    @Test fun competingReflectionDoesNotCertifyTimeAxis() {
        val s=synth(movingReflection=true);val result=SyncPolicy.assess(s.emitted,detect(s),config.highHz)
        assertFalse(result.findings.toString(),result.reliable)
    }
    @Test fun lowQualityRetainsArraysAndSuggestsExplicitContiguousBand() {
        val a=response();val limited=a.copy(snrDb=DoubleArray(4097) {if(it*48000.0/8192 in 500.0..1800.0) 40.0 else -5.0})
        val q=TransferEstimator.assess(listOf(limited,limited),listOf(limited,limited),config)
        assertFalse(q.eligible);assertEquals(4097,q.quality.reasons.size)
        assertTrue(q.quality.reasons.any {"LEFT_SNR" in it})
        assertNotNull(q.quality.suggestion);assertTrue(q.quality.suggestion!!.lowHz>=500)
        assertTrue(q.quality.suggestion!!.highHz<=1800)
        assertEquals(3400.0,config.highHz,0.0)
    }
    @Test fun symmetricResponsesWarnButDesignNonzeroReference() {
        val a=response();val pair=TransferEstimator.pair(listOf(a,a),listOf(a,a),config)
        assertEquals(Verdict.WARNING,MeasurementQuality.distinctChannels(pair)!!.verdict)
        val solution=AutoFirDesigner.design(pair,config,-24.0)
        assertEquals(1.0,solution.processing.left.fir.coefficients.sum(),0.0)
        assertTrue(solution.processing.right.fir.coefficients.any {abs(it)>0.1})
    }
    @Test fun frozenMaskCannotSilentlyDropBadBinsAndFailedVerificationKeepsNumbers() {
        val a=response();val both=response(gain=2.0);val corrected=response(gain=0.2)
        val mask=TransferEstimator.pair(listOf(a,a),listOf(a,a),config).weights
        val changed=a.copy(snrDb=a.snrDb.copyOf().also {it[100]=0.0})
        val v=MeasurementQuality.assessVerification(changed,both,corrected,mask)
        assertNotNull(v.result);assertFalse(v.passed);assertTrue(v.result!!.reductionSingleDb>10)
        assertTrue(mask[100]>0)
        val ineffective=MeasurementQuality.assessVerification(a,both,response(gain=0.95),mask)
        assertNotNull(ineffective.result);assertFalse(ineffective.passed)
    }
    @Test fun alternateNoiseIsDeterministicBandLimitedAndPeakBounded() {
        val noise=KnownProbe.noise(config)
        assertArrayEquals(noise,KnownProbe.noise(config),0.0)
        assertTrue(noise.maxOf {abs(it)}<=1.0)
        val spectrum=TransferEstimator.noiseSpectrum(noise)
        val inside=spectrum.indices.filter {it*48000.0/8192 in 400.0..3300.0}.sumOf {spectrum[it]}
        val outside=spectrum.indices.filter {it*48000.0/8192>4000}.sumOf {spectrum[it]}
        assertTrue(outside/inside<0.001)
    }
    @Test fun threeDbAloneCannotCommitAndReleasedRouteCannotClaimCalibration() {
        val s=Session(autoFir=config);val profile=CalibrationProfile(1,config,s.masterDb,s.swapped,5,3,true,s.processing,SessionRepository.encode(s),"{}")
        val good=AcceptanceEvidence(true,true,true,true,true,true)
        val results=List(4) {Verification(6.0,8.0,listOf(5.0),false)}
        for(bad in listOf(good.copy(timingReliable=false),good.copy(fixedMaskPreserved=false),good.copy(alternateSignalPassed=false),good.copy(playbackPathVerified=false)))
            assertThrows(IllegalArgumentException::class.java) {CalibrationTransaction(s,1).accept(profile,results,bad)}
        val accepted=CalibrationTransaction(s,1).accept(profile,results,good)
        assertTrue(CalibrationPlaybackPolicy.matches(profile,accepted,5,3,true))
        assertFalse(CalibrationPlaybackPolicy.matches(profile,accepted,5,0,false))
        assertFalse(CalibrationPlaybackPolicy.matches(profile,accepted.copy(processing=accepted.processing.copy(phaseDeg=90.0)),5,3,true))
    }
    @Test fun measuredFirThroughRealDspCancelsSyntheticPlantForSweepAndNoiseTwice() {
        val gain=dbGain(-24.0)
        fun capture(probe: DoubleArray, processing: Processing?, rightOnly: Boolean=false): Transfer {
            val size=probe.size+8192;val l=DoubleArray(size);val r=DoubleArray(size)
            if(processing==null) for(i in probe.indices) {if(rightOnly) r[i]=probe[i]*gain else l[i]=probe[i]*gain}
            else {
                val engine=DspEngine(PreparedProcessing.create(processing));val input=DoubleArray(BLOCK);val out=FloatArray(BLOCK*2)
                for(start in 0 until size step BLOCK) {
                    for(i in input.indices) input[i]=probe.getOrElse(start+i) {0.0}
                    engine.process(input,input,out,-24.0,false)
                    for(i in 0 until min(BLOCK,size-start)) {l[start+i]=out[i*2].toDouble();r[start+i]=out[i*2+1].toDouble()}
                }
                assertEquals(0L,engine.clips)
            }
            val recorded=DoubleArray(size)
            for(i in 100 until size) recorded[i]=l[i-20]+0.8*r[i-35]+0.1*l[i-100]
            val reference=DoubleArray(size) {probe.getOrElse(it) {0.0}*gain}
            return TransferEstimator.estimate(reference,recorded,1e-14)
        }
        val sweep=KnownProbe.generate(config);val left=capture(sweep,null);val right=capture(sweep,null,true)
        val pair=TransferEstimator.pair(listOf(left,left),listOf(right,right),config)
        val solution=AutoFirDesigner.design(pair,config,-24.0)
        val fixed=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(config.taps) {if(it==config.taps/2) 1.0 else 0.0})
        val both=Processing(length=config.taps,left=Channel(fir=fixed),right=Channel(fir=fixed))
        val single=both.copy(right=both.right.copy(muted=true))
        for(probe in listOf(sweep,KnownProbe.noise(config))) repeat(2) {
            val result=MeasurementQuality.assessVerification(capture(probe,single),capture(probe,both),capture(probe,solution.processing),pair.weights)
            assertTrue(result.findings.toString(),result.passed)
            assertTrue(result.result!!.reductionSingleDb>10)
        }
    }
    @Test fun gateAgcAndMidSweepLossFailEvenWhenPilotClockLooksStable() {
        val s=synth();assertTrue(SyncPolicy.assess(s.emitted,detect(s),config.highHz).reliable)
        val probe=KnownProbe.generate(config).copyOf(72000)
        val base=TransferEstimator.estimate(probe,probe,1e-14)
        val agc=DoubleArray(probe.size) {probe[it]*0.7}
        val gate=DoubleArray(probe.size) {if(it in 12000..36000) 0.0 else probe[it]}
        val loss=DoubleArray(probe.size) {if(it<24000) probe[it] else probe.getOrElse(it+100) {0.0}}
        for(recorded in listOf(agc,gate,loss)) {
            val changed=TransferEstimator.estimate(probe,recorded,1e-14)
            val q=TransferEstimator.assess(listOf(base,changed),listOf(base,base),config)
            assertFalse(q.findings.toString(),q.eligible)
            assertEquals(4097,q.quality.reasons.size)
        }
    }
}
