package com.hebowiz.speakerarray

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the dedicated no-audio emulator; hardware acoustic tests remain separately opt-in. */
@RunWith(AndroidJUnit4::class)
class PracticalAppTest {
    @Test fun toneSimplePlaybackRetainsMeasurementStreamWithoutFirProfile() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("retainedPlaybackTest")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val routes=DeviceRoutes(context) {};val out=routes.resolve(null,false);val input=routes.resolve(null,true)
        val settings=AutoFirSettings(output=out.key(),input=input.key(),seconds=1.0)
        val port=MeasurementAudio(context,out,input,settings) {};val engine=AudioEngine(context,{}, {})
        try {
            withTimeout(15000) {port.open()}
            val initial=Session(autoFir=settings,masterDb=-60.0,source=SourceSettings(frequency=750.0),
                correctionMode=CorrectionMode.SIMPLE,simple=SimpleCorrection(sinePhaseDeg=-105.0,right=Channel(gainDb=4.0)))
            engine.settings=initial;port.retain()
            val route=port.state().actual
            engine.startRetained(PreparedProcessing.create(initial.effectiveProcessing(),1,true),null,port)
            delay(400);assertTrue(engine.isRunning);assertEquals(route,port.state().actual)
            engine.settings=initial.copy(correctionMode=CorrectionMode.FIR)
            engine.submit(PreparedProcessing.create(initial.processing,2,true))
            delay(250);assertTrue(engine.isRunning)
            engine.settings=initial;engine.submit(PreparedProcessing.create(initial.effectiveProcessing(),3,true))
            delay(250);assertTrue(engine.isRunning);assertEquals(route,port.state().actual)
            port.close();withTimeout(5000) {while(engine.isRunning) delay(20)}
        } finally {engine.stop();port.close();routes.close()}
    }
    @Test fun sameMeasuredStreamPlaybackStopsOnChangedCoefficientsAndRelease() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("retainedPlaybackTest")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        val routes=DeviceRoutes(context) {}
        val output=routes.resolve(null,false);val input=routes.resolve(null,true)
        val settings=AutoFirSettings(output=output.key(),input=input.key(),seconds=1.0)
        val io=MeasurementAudio(context,output,input,settings) {}
        val engine=AudioEngine(context,{}, {})
        try {
            withTimeout(15000) {io.open()}
            val p=Processing()
            val original=Session(autoFir=settings,masterDb=-60.0,processing=p,orientationChosen=true)
            val profile=CalibrationProfile(1,settings,-60.0,false,io.volumeIndex,io.mode,false,p,SessionRepository.encode(original),"{}")
            engine.settings=original.copy(calibration=profile);io.retain()
            val route=io.state().actual
            engine.startRetained(PreparedProcessing.create(p,1,true),null,io)
            delay(400);assertTrue(engine.isRunning);assertEquals(route,io.state().actual)
            engine.settings=engine.settings.copy(correctionMode=CorrectionMode.SIMPLE,simple=SimpleCorrection(sinePhaseDeg=90.0))
            engine.submit(PreparedProcessing.create(engine.settings.effectiveProcessing(),2,true))
            delay(300);assertTrue(engine.isRunning);assertEquals(route,io.state().actual)
            engine.settings=original.copy(calibration=profile)
            engine.submit(PreparedProcessing.create(p,3,true))
            delay(300);assertTrue(engine.isRunning);io.check()
            engine.settings=engine.settings.copy(processing=p.copy(delayUs=100.0))
            withTimeout(5000) {while(engine.isRunning) delay(20)}
            engine.settings=original.copy(calibration=profile)
            engine.startRetained(PreparedProcessing.create(p,1,true),null,io)
            delay(300);engine.stop()
            withTimeout(5000) {while(engine.isRunning) delay(20)}
            io.check()
            engine.startRetained(PreparedProcessing.create(p,1,true),null,io)
            delay(300);io.close()
            withTimeout(5000) {while(engine.isRunning) delay(20)}
            assertFalse(CalibrationPlaybackPolicy.matches(profile,engine.settings,io.volumeIndex,io.mode,false))
        } finally {engine.stop();io.close();routes.close()}
        assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
    }
    @Test fun failedDiagnosticsRoundTripFullResolutionAndUnknownVerification() {
        val t=Transfer(DoubleArray(4097) {1.0},DoubleArray(4097),DoubleArray(4097) {-10.0})
        val q=TransferEstimator.assess(listOf(t,t),listOf(t,t),AutoFirSettings())
        val diagnostic=JSONObject().put("schemaVersion",2).put("thresholds",MeasurementResultStore.thresholds())
            .put("quality",MeasurementResultStore.pair(q)).put("reasonCode","BAND_QUALITY")
        val read=JSONObject(diagnostic.toString())
        assertEquals(4097,read.getJSONObject("quality").getJSONArray("leftRepeatPhaseDeg").length())
        assertEquals(4097,read.getJSONObject("quality").getJSONObject("left").getJSONArray("snrDb").length())
        assertFalse(read.getJSONObject("quality").getBoolean("eligible"))
        assertFalse(read.has("verification"))
    }
}
