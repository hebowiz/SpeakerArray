package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.math.*

class AutoFirTest {
    private val settings=AutoFirSettings(highHz=3400.0)
    private fun transfer(vararg taps: Pair<Int,Double>): Transfer {
        val re=DoubleArray(TransferEstimator.SIZE);val im=DoubleArray(re.size)
        taps.forEach {(index,value)->re[index]=value};Fft.transform(re,im)
        return Transfer(re.copyOf(re.size/2+1),im.copyOf(im.size/2+1),DoubleArray(re.size/2+1) {60.0})
    }
    private fun pair(left: Transfer, right: Transfer)=TransferEstimator.pair(listOf(left,left),listOf(right,right),settings)
    @Test fun causalFiniteFilterCancelsGainDelayAndReflectionsWithoutZeroReference() {
        val measured=pair(transfer(20 to 0.8,120 to 0.18),transfer(31 to 1.0,170 to -0.12))
        for(referenceRight in listOf(false,true)) {
            val solution=AutoFirDesigner.design(measured,settings.copy(referenceRight=referenceRight),-24.0)
            val reference=if(referenceRight) solution.processing.right else solution.processing.left
            assertEquals(1.0,reference.fir.coefficients[512],0.0)
            assertEquals(1.0,reference.fir.coefficients.sum(),0.0)
            assertTrue(solution.predictedDb < -15.0)
            assertTrue(solution.maxBoostDb <= 6.0);assertTrue(solution.peakBound<0.9)
            val prepared=PreparedProcessing.create(solution.processing,100)
            assertEquals(solution.processing,prepared.parameters)
            assertTrue(prepared.left.re.all {it.isFinite()})
        }
    }
    @Test fun independentlyPeakAlignedPathsProduceWrongRelativePhase() {
        val correct=pair(transfer(40 to 1.0),transfer(60 to 1.0))
        val solution=AutoFirDesigner.design(correct,settings,-24.0)
        val h=solution.processing.right.fir.coefficients
        assertEquals(492,h.indices.maxBy {abs(h[it])})
        val wrong=AutoFirDesigner.design(pair(transfer(0 to 1.0),transfer(0 to 1.0)),settings,-24.0)
        assertEquals(512,wrong.processing.right.fir.coefficients.indices.maxBy {abs(wrong.processing.right.fir.coefficients[it])})
    }
    @Test fun largeCommonLatencyAndSmoothClockDriftUseOneTimeMap() {
        val emitted=listOf(48000.0,240000.0,480000.0,720000.0)
        val captured=emitted.map {it*1.0001+55000.0}
        val fit=MeasurementSync.fit(emitted,captured)
        assertEquals(55000.0,fit.offset,1e-7);assertEquals(100.0,fit.ppm,1e-6)
        assertEquals(0.0,fit.residual,1e-7)
        assertEquals(21.0,(fit.offset+fit.ratio*100021-(fit.offset+fit.ratio*100000))/fit.ratio,1e-7)
    }
    @Test fun latencyJumpAndMissingFramesAreRejected() {
        val emitted=listOf(10000.0,100000.0,200000.0,300000.0)
        for(jump in listOf(-480.0,48.0,160.0)) {
            assertThrows(IllegalArgumentException::class.java) {MeasurementSync.fit(emitted,emitted.mapIndexed {i,v->v+45000+if(i>=2) jump else 0.0})}
        }
    }
    @Test fun resamplingDifferentInputRatePreservesToneAndDelay() {
        for(rate in listOf(16000,44100,48000)) {
            val input=DoubleArray(rate) {sin(2*PI*1000*it/rate)}
            for(i in 1000..2000) {
                val sample=MeasurementSync.sample(input,i*rate/48000.0)
                assertEquals(sin(2*PI*1000*i/48000.0),sample,0.0005)
            }
        }
    }
    @Test fun matchedPilotLocatesLongLatency() {
        val pilot=KnownProbe.pilot(settings)
        val data=DoubleArray(100000)
        for(i in pilot.indices) data[57000+i]=0.1*pilot[i]
        assertEquals(57000.0,MeasurementSync.locate(data,pilot,20000,95000),0.01)
    }
    @Test fun deconvolutionRetainsReflectionsAndRelativeDelay() {
        val signal=KnownProbe.generate(settings.copy(seconds=1.0))
        val reference=signal.copyOf(signal.size+24000)
        val recorded=DoubleArray(reference.size)
        for(i in signal.indices) {recorded[i+20]+=signal[i]*0.5;recorded[i+2000]+=signal[i]*0.1}
        val measured=TransferEstimator.estimate(reference,recorded,1e-14)
        val expected=transfer(20 to 0.5,2000 to 0.1)
        for(k in 100..500) {assertEquals(expected.re[k],measured.re[k],1e-8);assertEquals(expected.im[k],measured.im[k],1e-8)}
    }
    @Test fun agcGateAndPhaseInstabilityFailCoverage() {
        val a=transfer(0 to 1.0)
        for(b in listOf(transfer(0 to 0.7),transfer(0 to 0.0),transfer(3 to 1.0))) {
            assertThrows(IllegalArgumentException::class.java) {TransferEstimator.pair(listOf(a,b),listOf(a,a),settings)}
        }
    }
    @Test fun hfpMissingBandCannotClaimWidebandSuccess() {
        val a=transfer(0 to 1.0)
        val limited=a.copy(snrDb=DoubleArray(a.re.size) {k->if(k*48000.0/8192 in 300.0..3400.0) 40.0 else -40.0})
        assertTrue(pair(limited,limited).coverage>0.99)
        assertThrows(IllegalArgumentException::class.java) {TransferEstimator.pair(listOf(limited,limited),listOf(limited,limited),settings.copy(highHz=8000.0))}
    }
    @Test fun deepNullDoesNotCreateUnboundedInverse() {
        val measured=pair(transfer(0 to 1.0),transfer(0 to 1.0,16 to -0.999))
        assertThrows(IllegalArgumentException::class.java) {AutoFirDesigner.design(measured,settings,-30.0)}
        val solution=AutoFirDesigner.design(measured,settings.copy(regularization=0.1),-30.0)
        assertTrue(solution.maxBoostDb<=6.0)
        assertTrue(solution.processing.right.fir.coefficients.all {it.isFinite()})
    }
    @Test fun inputRejectsClipsSilenceAndMissingData() {
        for(pcm in listOf(doubleArrayOf(),DoubleArray(100),doubleArrayOf(0.1,1.0),doubleArrayOf(Double.NaN)))
            assertThrows(IllegalArgumentException::class.java) {MeasurementQuality.input(pcm)}
    }
    @Test fun verificationNeedsBothBaselinesAndNoBadSubband() {
        val single=transfer(0 to 1.0);val both=transfer(0 to 2.0);val good=transfer(0 to 0.2)
        val weights=pair(single,both).weights
        val result=MeasurementQuality.verify(single,both,good,weights)
        assertTrue(result.reductionSingleDb>10)
        assertThrows(IllegalArgumentException::class.java) {MeasurementQuality.verify(single,both,transfer(0 to 0.9),weights)}
        val bad=good.copy(recordedPower=good.recordedPower.copyOf().also {for(k in 120..140) it[k]=100.0})
        assertThrows(IllegalArgumentException::class.java) {MeasurementQuality.verify(single,both,bad,weights)}
        val silent=single.copy(snrDb=DoubleArray(single.re.size) {-10.0})
        assertThrows(IllegalArgumentException::class.java) {MeasurementQuality.verify(silent,both,good,weights)}
    }
    @Test fun oldSessionsMigrateAndCalibrationBackupPreservesABAndArbitraryCoefficients() {
        val p=Processing(length=257,phaseDeg=90.0,delayUs=-123.0,left=Channel(gainDb=-3.0,inverted=true))
        val old=Session(processing=p,a=p,b=p.copy(phaseDeg=-90.0),masterDb=-27.0,swapped=true)
        val legacy=JSONObject(SessionRepository.encode(old)).put("schemaVersion",1).apply {remove("autoFir");remove("calibration")}.toString()
        assertEquals(old,SessionRepository.decode(legacy))
        val applied=AutoFirDesigner.design(pair(transfer(0 to 1.0),transfer(4 to 1.0)),settings,-27.0).processing
        val profile=CalibrationProfile(42,settings,old.masterDb,old.swapped,5,0,false,applied,SessionRepository.encode(old),"{\"ok\":true}")
        val current=old.copy(processing=applied,a=null,b=null,autoFir=settings,calibration=profile)
        val saved=SessionRepository.decode(SessionRepository.encode(current))
        assertEquals(applied,saved.processing);assertEquals(old,SessionRepository.decode(saved.calibration!!.backup))
        assertEquals(42L,saved.calibration!!.generation)
        assertTrue(profile.matches(current,5));assertFalse(profile.matches(current.copy(masterDb=-20.0),5))
    }
    @Test fun designCancellationIsNotAnAcceptedSolution() {
        var checks=0
        assertThrows(IllegalStateException::class.java) {AutoFirDesigner.design(pair(transfer(0 to 1.0),transfer(20 to 1.0)),settings,-24.0) {if(++checks==2) error("cancel")}}
    }
    @Test fun indistinguishableChannelsCannotCertifyStereo() {
        assertThrows(IllegalArgumentException::class.java) {MeasurementQuality.distinctChannels(pair(transfer(0 to 1.0),transfer(0 to 1.0)))}
        MeasurementQuality.distinctChannels(pair(transfer(0 to 1.0),transfer(20 to 0.8)))
    }
    @Test fun rollbackPreservesFullSessionAndLateGenerationCannotCommit() {
        val original=Session(processing=Processing(phaseDeg=90.0),a=Processing(),b=Processing(delayUs=5.0),swapped=true)
        val applied=Processing(length=1025)
        val profile=CalibrationProfile(42,settings,original.masterDb,true,5,0,false,applied,SessionRepository.encode(original),"{}")
        val verified=List(2) {Verification(6.0,9.0,listOf(4.0,5.0),false)}
        for(stage in listOf("route","measurement","design","trial","verification")) {
            val transaction=CalibrationTransaction(original,42)
            assertEquals(stage,original,transaction.rollback())
            assertThrows(IllegalStateException::class.java) {transaction.accept(profile,verified)}
        }
        assertThrows(IllegalStateException::class.java) {CalibrationTransaction(original,43).accept(profile,verified)}
        assertThrows(IllegalArgumentException::class.java) {CalibrationTransaction(original,42).accept(profile,verified.take(1))}
        val accepted=CalibrationTransaction(original,42).accept(profile,verified)
        assertEquals(applied,accepted.processing);assertNull(accepted.a);assertNull(accepted.b)
        assertEquals(original,SessionRepository.decode(accepted.calibration!!.backup))
    }
}
