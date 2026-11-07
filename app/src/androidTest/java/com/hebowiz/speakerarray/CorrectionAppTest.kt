package com.hebowiz.speakerarray

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Dedicated no-audio emulator only. Hardware tests have a separate opt-in method. */
class CorrectionAppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun init() {
        rule.runOnIdle {vm.stop();vm.dismissMessage();vm.update {Session(orientationChosen=true)};vm.tab=0}
        rule.waitUntil(10000) {vm.draftCurve!=null};Thread.sleep(300)
    }
    @Test fun activeSwitchDoesNotRestartTrackAndSoloDoesNotAlterSlots() {
        init()
        val fir=Processing(phaseDeg=90.0,right=Channel(gainDb=-3.0))
        val simple=SimpleCorrection(sinePhaseDeg=-90.0,right=Channel(gainDb=-6.0))
        rule.runOnIdle {vm.update {it.copy(processing=fir,simple=simple)};vm.storeAB(false)}
        Thread.sleep(300);rule.runOnIdle {vm.dismissMessage();vm.start()}
        rule.waitUntil(15000) {vm.audio.playing && vm.audio.message=="再生"}
        val thread=Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"}
        val route=vm.audio.route
        repeat(10) {i->rule.runOnIdle {vm.correction(if(i%2==0) CorrectionMode.SIMPLE else CorrectionMode.FIR)};Thread.sleep(80)}
        rule.waitUntil(10000) {vm.audio.generation==vm.generation && !vm.audio.switching}
        assertSame(thread,Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"})
        assertEquals(route,vm.audio.route);assertEquals(0,vm.audio.clips)
        rule.runOnIdle {vm.solo(true);vm.correction(CorrectionMode.SIMPLE)};Thread.sleep(200)
        assertEquals(fir,vm.session.processing);assertEquals(simple,vm.session.simple)
        rule.runOnIdle {vm.solo(true);vm.recallAB(false)}
        assertEquals(CorrectionMode.FIR,vm.session.correctionMode)
        assertEquals(fir,vm.session.processing);assertEquals(simple,vm.session.simple)
        rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.audio.playing}
        val saved=SessionRepository.encode(vm.session)
        rule.runOnIdle {vm.loadSession(saved)}
        assertEquals(fir,vm.session.processing);assertEquals(simple,vm.session.simple)
        rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
        rule.onNodeWithText("位相・振幅",substring=false).assertIsDisplayed()
    }
    @Test fun wavPositionAndWorkerSurviveModeChanges() {
        init()
        val file=File(rule.activity.cacheDir,"switch-test.pcm")
        // 12 seconds of normalized, already decoded PCM; the same reader must keep advancing.
        file.outputStream().use {out->val b=java.nio.ByteBuffer.allocate(48000*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(48000) {b.putFloat((0.1*kotlin.math.sin(2*Math.PI*500*it/48000)).toFloat())};repeat(12) {out.write(b.array())}}
        val engine=AudioEngine(rule.activity,{}, {})
        val routes=DeviceRoutes(rule.activity) {}
        try {
            val original=Session(source=SourceSettings(kind=SourceKind.WAV,loop=false))
            engine.settings=original;engine.requestedOutput=routes.resolve(null,false)
            engine.start(PreparedProcessing.create(original.processing,1),file)
            Thread.sleep(600)
            val worker=Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"}
            val wavField=AudioEngine::class.java.getDeclaredField("wav").apply {isAccessible=true}
            val reader=wavField.get(engine) as WavSource
            val before=reader.positionFrames
            repeat(6) {i->val mode=if(i%2==0) CorrectionMode.SIMPLE else CorrectionMode.FIR
                engine.settings=original.copy(correctionMode=mode)
                engine.submit(PreparedProcessing.create(engine.settings.effectiveProcessing(),(i+2).toLong()));Thread.sleep(100)}
            assertSame(reader,wavField.get(engine));assertTrue(reader.positionFrames>before)
            assertSame(worker,Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"})
        } finally {engine.stop();Thread.sleep(400);routes.close();file.delete()}
    }
    @Test fun invalidImportedRouteCannotStartAndMeasurementLocksBothSlots() {
        init()
        val invalid=Session(orientationChosen=true,autoFir=AutoFirSettings(output=EndpointKey(8,"missing BT","a"),input=EndpointKey(7,"BT mic","b")))
        rule.runOnIdle {vm.loadSession(SessionRepository.encode(invalid));vm.confirmMapping(false);vm.startAuto()}
        assertFalse(vm.autoBusy);assertNotNull(vm.measurementBlockReason());assertEquals(invalid,vm.session)
        rule.runOnIdle {vm.dismissMessage();vm.update {Session(orientationChosen=true,correctionMode=CorrectionMode.SIMPLE)};vm.confirmMapping(false)}
        val original=vm.session
        rule.runOnIdle {vm.startAuto();if(vm.autoBusy) {vm.correction(CorrectionMode.FIR);assertEquals(original,vm.session)};vm.cancelAuto()}
        rule.waitUntil(10000) {!vm.autoBusy}
        assertEquals(original,vm.session)
    }
}
