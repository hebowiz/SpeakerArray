package com.hebowiz.speakerarray

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.*

/** Synthetic diagnostic fixture; all audio operations are restricted by opt-in to a silent emulator. */
class ExperimentalAppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun init(reset: Boolean=true) {
        check(InstrumentationRegistry.getArguments().getString("silentEmulator")=="true")
        rule.runOnIdle {vm.stop();vm.releaseHeldRoute();vm.dismissMessage();vm.tab=3;vm.experimentalPage=true
            if(reset) vm.loadSession(SessionRepository.encode(Session(orientationChosen=true,masterDb=-60.0,
                processing=Processing(513,phaseDeg=45.0),simple=SimpleCorrection(sinePhaseDeg=-90.0,right=Channel(gainDb=-3.0)))))}
        rule.waitUntil(10000) {!vm.preparing && !vm.engine.isRunning};Thread.sleep(300)
    }
    private fun diagnostic(): String {
        val s=AutoFirSettings(lowHz=500.0,highHz=1000.0,taps=257)
        val order=JSONArray((0..3).map {i->JSONObject().put("tag",if(i%2==0) "L" else "R").put("pilotBefore",i*200000L)
            .put("signalFrame",i*200000L+24000).put("pilotAfter",i*200000L+170000).put("signalFrames",144000)})
        val t=JSONObject().put("method",SyncPolicy.VERSION).put("analyzable",true).put("reliable",false).put("offset48k",12000.0)
            .put("ratio",1.0).put("clockPpm",0.0).put("residual48k",1.0).put("rmsResidual48k",.5)
            .put("excluded",JSONArray()).put("pilots",JSONArray(List(8) {JSONObject().put("correlation",.5)}))
            .put("findings",JSONArray().put(JSONObject().put("code","PILOT_AMBIGUOUS")))
        val transfers=JSONArray((0..3).map {i->val right=i>=2
            JSONObject().put("real",JSONArray(List(4097) {k->if(right) .8*cos(-2*PI*k*12/8192) else 1.0}))
                .put("imaginary",JSONArray(List(4097) {k->if(right) .8*sin(-2*PI*k*12/8192) else 0.0}))
                .put("snrDb",JSONArray(List(4097) {k->if(k%3==0) 10.0 else 60.0})).put("recordedPower",JSONArray(List(4097) {.001}))})
        return JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",2).put("frequencyStepHz",48000.0/8192)
            .put("settings",CalibrationRepository.settings(s)).put("masterDb",-60.0).put("clips",0).put("measurementOrder",order)
            .put("initialTiming",t).put("initialTransfers",transfers).put("stage","REJECTED").put("reasonCode","BAND_QUALITY").toString()
    }
    private fun generate() {
        rule.runOnIdle {vm.selectExperimentalData(diagnostic());vm.generateExperimental()}
        rule.waitUntil(15000) {!vm.autoBusy}
        assertTrue(vm.experimentalStatus,vm.session.experiments.current()?.playable==true)
    }
    private fun shell(command: String) {InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {java.io.FileInputStream(it.fileDescriptor).readBytes()}}
    @Test fun generateApplyUndoImportAndPlaybackNeedNoMicAndPreserveBothSlots() {
        init();assertEquals(PackageManager.PERMISSION_DENIED,rule.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        rule.runOnIdle {vm.storeAB(false);vm.dismissMessage()};val before=vm.session
        val record=AudioResourceUsage.recordingStarts.get();val communication=AudioResourceUsage.communicationStarts.get()
        generate();assertEquals(before.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple)
        assertEquals(before.correctionA,vm.session.correctionA);assertEquals(before.calibration,vm.session.calibration)
        val c=vm.session.experiments.current()!!;val hash=c.coefficientHash
        val exported=vm.exportExperimental();assertEquals(hash,ExperimentalRepository.import(exported).coefficientHash)
        rule.runOnIdle {vm.applyExperimental()};rule.waitUntil(10000) {!vm.preparing}
        assertEquals(c.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple);assertEquals(before.a,vm.session.a)
        assertTrue(vm.calibrationStatus().contains("実験用FIR"));assertTrue(vm.calibrationStatus().contains("未検証"))
        rule.runOnIdle {vm.start()};rule.waitUntil(10000) {vm.audio.playing && vm.audio.message=="再生"}
        rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.engine.isRunning}
        val saved=SessionRepository.encode(vm.session)
        rule.runOnIdle {vm.loadSession(saved)};rule.waitUntil(10000) {!vm.preparing}
        assertEquals(hash,vm.session.experiments.current()!!.coefficientHash);assertTrue(vm.calibrationStatus().contains("未検証"))
        rule.runOnIdle {vm.undoExperimental()};rule.waitUntil(10000) {!vm.preparing}
        assertEquals(before.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple);assertEquals(before.correctionA,vm.session.correctionA)
        rule.runOnIdle {vm.removeExperimentalCandidate();vm.selectExperimentalData(exported)}
        assertEquals(hash,vm.session.experiments.current()!!.coefficientHash)
        assertEquals(record,AudioResourceUsage.recordingStarts.get());assertEquals(communication,AudioResourceUsage.communicationStarts.get())
    }
    @Test fun comparisonPermissionFailureAndImmediateCancelsNeverApply() {
        init();generate();val original=vm.session;val hash=original.experiments.current()!!.coefficientHash
        rule.runOnIdle {vm.confirmMapping(false);vm.compareExperimental();vm.correction(CorrectionMode.SIMPLE)}
        rule.waitUntil(15000) {!vm.autoBusy}
        assertTrue(vm.experimentalStatus,vm.experimentalStatus.contains("中止"));assertEquals(original.processing,vm.session.processing)
        assertEquals(original.simple,vm.session.simple);assertEquals(hash,vm.session.experiments.current()!!.coefficientHash)
        assertTrue(vm.session.experiments.current()!!.comparisons.isNotEmpty());assertFalse(vm.audio.playing)
        rule.runOnIdle {vm.selectExperimentalData(diagnostic());vm.generateExperimental();vm.cancelAuto()}
        rule.waitUntil(10000) {!vm.autoBusy};assertEquals(original.processing,vm.session.processing)
        rule.runOnIdle {vm.confirmMapping(false);vm.compareExperimental();vm.cancelAuto()}
        rule.waitUntil(10000) {!vm.autoBusy};assertEquals(original.processing,vm.session.processing);assertEquals(original.simple,vm.session.simple)
    }
    @Test fun seedExperimentalForProcessRestart() {
        init();generate();rule.runOnIdle {vm.applyExperimental();vm.persist()};Thread.sleep(700)
        rule.activity.getSharedPreferences("experimental-test",0).edit().putInt("pid",android.os.Process.myPid())
            .putString("hash",vm.session.experiments.current()!!.coefficientHash).commit()
    }
    @Test fun restoreExperimentalInNewProcess() {
        init(false);val prefs=rule.activity.getSharedPreferences("experimental-test",0)
        assertNotEquals(prefs.getInt("pid",-1),android.os.Process.myPid())
        assertEquals(prefs.getString("hash",null),vm.session.experiments.current()!!.coefficientHash)
        assertFalse(vm.maintainingRoute);assertTrue(vm.calibrationStatus().contains("未検証"))
        rule.runOnIdle {vm.start()};rule.waitUntil(10000) {vm.audio.playing && vm.audio.message=="再生"}
        rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.engine.isRunning}
    }
    @Test fun silentComparisonRejectsAbsentSignalAndGuiRetainsStopAndResults() {
        init();generate();val original=vm.session
        shell("pm grant com.hebowiz.speakerarray android.permission.RECORD_AUDIO")
        try {
            rule.runOnIdle {vm.confirmMapping(false);vm.compareExperimental()}
            rule.waitUntil(35000) {!vm.autoBusy}
            assertTrue(vm.experimentalStatus,vm.experimentalStatus.contains("測定不能"))
            assertEquals(original.processing,vm.session.processing);assertEquals(original.simple,vm.session.simple)
            assertEquals(original.experiments.current()!!.coefficientHash,vm.session.experiments.current()!!.coefficientHash)
            assertFalse(vm.maintainingRoute);assertFalse(vm.engine.isRunning)
            assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
            shell("wm size 960x440");shell("wm density 160");Thread.sleep(1000)
            rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
            rule.onNodeWithText("取消",substring=false).assertIsDisplayed()
            rule.onNodeWithText("候補・結果を保存",substring=false).performScrollTo().assertIsDisplayed()
            val image=rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(rule.activity.getExternalFilesDir(null),"experimental-ui.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            rule.onNodeWithContentDescription("有限長FIRの予測・反復1").performScrollTo().assertIsDisplayed()
            val graph=rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(rule.activity.getExternalFilesDir(null),"experimental-graph.png").outputStream().use {graph.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            val saved=vm.exportExperimental();assertTrue(saved.contains("ABORTED"));assertTrue(saved.contains("formalAccepted"))
            shell("wm size 320x700");Thread.sleep(800)
            rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
        } finally {rule.runOnIdle {vm.stop()};shell("wm size reset");shell("wm density reset")}
    }
}
