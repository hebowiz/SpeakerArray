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
    private fun diagnostic(wide: Boolean=false): String {
        val s=AutoFirSettings(lowHz=if(wide) 300.0 else 500.0,highHz=if(wide) 3400.0 else 1000.0,taps=257)
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
    @Test fun pocBadQualityOutputBoundCanApplyAtCurrentLevelAndUndo() {
        init();val before=vm.session
        val d=JSONObject(diagnostic(true)).put("reasonCode","OUTPUT_BOUND").put("masterDb",0.0)
        val transfers=d.getJSONArray("initialTransfers")
        for(i in 0..3) transfers.getJSONObject(i).put("snrDb",JSONArray(List(4097) {-80.0}))
        d.getJSONObject("initialTiming").put("residual48k",20.0).put("reliable",false)
        rule.runOnIdle {vm.selectExperimentalData(d.toString());vm.generateExperimental()}
        rule.waitUntil(20000) {!vm.autoBusy}
        val c=vm.session.experiments.current()!!
        assertTrue(vm.experimentalStatus,c.playable);assertTrue(c.comparisons.isEmpty())
        assertEquals(0.0,JSONObject(c.report).getDouble("trialCoverage"),0.0)
        assertEquals(before.processing,vm.session.processing)
        rule.runOnIdle {vm.update {it.copy(masterDb=0.0)};vm.applyExperimental()}
        assertEquals(before.processing,vm.session.processing);assertTrue(vm.message,vm.message.contains("DSPピーク"))
        rule.runOnIdle {vm.dismissMessage();vm.update {it.copy(masterDb=-30.0)};vm.applyExperimental()}
        rule.waitUntil(10000) {!vm.preparing}
        assertEquals(c.processing,vm.session.processing);assertEquals(c.processing,vm.engine.settings.effectiveProcessing())
        assertEquals(0.0,vm.session.experiments.current()!!.masterDb,0.0)
        assertFalse(vm.maintainingRoute);assertFalse(vm.audio.playing)
        rule.runOnIdle {vm.undoExperimental()};rule.waitUntil(10000) {!vm.preparing}
        assertEquals(before.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple)
        val saved=vm.session
        val broken=JSONObject(d.toString()).apply {remove("initialTransfers")}
        rule.runOnIdle {vm.selectExperimentalData(broken.toString());vm.generateExperimental()}
        assertEquals(saved,vm.session);assertTrue(vm.experimentalStatus.contains("不足"))
    }
    @Test fun savedWidebandCandidatesApplyExactlyWithoutComparisonOrMic() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("widebandFixtures")=="true")
        init();val before=vm.session
        val recordings=AudioResourceUsage.recordingStarts.get();val communications=AudioResourceUsage.communicationStarts.get()
        for((name,id) in listOf("poc-candidate1.json" to "cae34bb91b6a53d07048aac6","poc-candidate2.json" to "7085475b8e7b33caac6c293e")) {
            val raw=java.io.File(rule.activity.filesDir,name).readText()
            rule.runOnIdle {vm.selectExperimentalData(raw)}
            val c=vm.session.experiments.current()!!;assertEquals(id,c.id)
            if(name.contains("2")) assertTrue(c.comparisons.all {JSONObject(it).getString("outcome")=="INSUFFICIENT_REDUCTION"})
            rule.runOnIdle {vm.applyExperimental()};rule.waitUntil(15000) {!vm.preparing}
            assertEquals(c.processing,vm.session.processing);assertEquals(c.processing,vm.engine.settings.effectiveProcessing())
            assertEquals(CorrectionMode.FIR,vm.session.correctionMode);assertFalse(vm.audio.playing)
            val encoded=SessionRepository.encode(vm.session)
            rule.runOnIdle {vm.loadSession(encoded)};rule.waitUntil(15000) {!vm.preparing}
            assertEquals(c.coefficientHash,processingHash(vm.session.processing));assertEquals(id,vm.session.experiments.applied)
            rule.runOnIdle {vm.start()};rule.waitUntil(10000) {vm.audio.playing && vm.audio.message=="再生"}
            rule.runOnIdle {vm.stop()};rule.waitUntil(10000) {!vm.engine.isRunning}
            rule.runOnIdle {vm.undoExperimental()};rule.waitUntil(10000) {!vm.preparing}
            assertEquals(before.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple)
        }
        assertEquals(recordings,AudioResourceUsage.recordingStarts.get());assertEquals(communications,AudioResourceUsage.communicationStarts.get())
    }
    @Test fun pocMeasurementCancelKeepsOldSettingsAndMainListeningControlsAreReachable() {
        init();val before=vm.session
        rule.runOnIdle {vm.experimentalPage=false;vm.confirmMapping(false);vm.startListening();vm.cancelAuto()}
        rule.waitUntil(15000) {!vm.autoBusy}
        assertEquals(before.processing,vm.session.processing);assertEquals(before.simple,vm.session.simple)
        assertFalse(vm.maintainingRoute);assertFalse(vm.engine.isRunning)
        generate()
        rule.runOnIdle {vm.experimentalPage=false}
        rule.onNodeWithText("測定してFIRを作成").assertExists()
        rule.onNodeWithText("FIRとして反映").assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(10000) {!vm.preparing}
        rule.onNodeWithText("反映前に戻す").assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(10000) {!vm.preparing}
        assertEquals(before.processing,vm.session.processing)
    }
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
        init();generate();rule.runOnIdle {vm.applyExperimental()}
        val c=vm.session.experiments.current()!!
        val run=ExperimentalRuns.start(c).put("stage","CANCELLED").toString()
        rule.runOnIdle {vm.loadSession(SessionRepository.encode(vm.session.copy(experiments=vm.session.experiments.copy(candidates=listOf(c.copy(comparisons=listOf(run)))))))}
        rule.waitUntil(10000) {!vm.preparing}
        rule.runOnIdle {val a=vm.prepareExperimentalArchive()!!;vm.completeExperimentalArchive(a.token,true);vm.pruneExperimentalHistory(setOf(ExperimentalRuns.id(run)));vm.persist()};Thread.sleep(700)
        rule.activity.getSharedPreferences("experimental-test",0).edit().putInt("pid",android.os.Process.myPid())
            .putString("hash",vm.session.experiments.current()!!.coefficientHash).putString("oldRunId",ExperimentalRuns.id(run)).commit()
    }
    @Test fun restoreExperimentalInNewProcess() {
        init(false);val prefs=rule.activity.getSharedPreferences("experimental-test",0)
        assertNotEquals(prefs.getInt("pid",-1),android.os.Process.myPid())
        assertEquals(prefs.getString("hash",null),vm.session.experiments.current()!!.coefficientHash)
        assertFalse(vm.maintainingRoute);assertTrue(vm.calibrationStatus().contains("未検証"))
        rule.runOnIdle {vm.start()};rule.waitUntil(10000) {vm.audio.playing && vm.audio.message=="再生"}
        rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.engine.isRunning}
        assertNotEquals(prefs.getString("oldRunId",null),ExperimentalRuns.start(vm.session.experiments.current()!!).getString("runId"))
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
    @Test fun archiveFailureCancellationAndExplicitCleanupAllowSameCandidateRetry() {
        init();generate()
        rule.runOnIdle {vm.storeAB(false);vm.dismissMessage();vm.applyExperimental()};rule.waitUntil(10000) {!vm.preparing}
        val c=vm.session.experiments.current()!!
        val full=c.copy(comparisons=listOf("ABORTED","CANCELLED","COMPLETED").map {stage->JSONObject()
            .put("candidateId",c.id).put("coefficientHash",c.coefficientHash).put("formalAccepted",false).put("stage",stage).toString()})
        val session=vm.session.copy(experiments=vm.session.experiments.copy(candidates=listOf(full)))
        rule.runOnIdle {vm.loadSession(SessionRepository.encode(session))};rule.waitUntil(10000) {!vm.preparing}
        val before=vm.session;val ids=before.experiments.current()!!.comparisons.map(ExperimentalRuns::id)
        rule.runOnIdle {vm.confirmMapping(false);vm.compareExperimental()};assertFalse(vm.autoBusy)
        rule.runOnIdle {vm.dismissMessage()}
        rule.onNodeWithText("比較履歴を退避").performScrollTo().performClick()
        Thread.sleep(1000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        rule.waitUntil(5000) {vm.message.contains("退避は未完了")}
        assertEquals(before,vm.session)
        rule.runOnIdle {vm.dismissMessage();vm.prepareExperimentalArchive()}
        val token=vm.experimentalArchive!!.token
        rule.runOnIdle {vm.completeExperimentalArchive(token,false);vm.pruneExperimentalHistory(setOf(ids[0]))}
        assertEquals(before,vm.session)
        rule.runOnIdle {vm.saveExperimentalArchive(token) {throw java.io.IOException("fixture write failure")}}
        rule.waitUntil(5000) {!vm.experimentalArchiveWriting};assertFalse(vm.experimentalArchive!!.saved);assertEquals(before,vm.session)
        rule.runOnIdle {vm.saveExperimentalArchive(token) {throw kotlinx.coroutines.CancellationException("fixture cancel")}}
        rule.waitUntil(5000) {!vm.experimentalArchiveWriting};assertFalse(vm.experimentalArchive!!.saved);assertEquals(before,vm.session)
        rule.runOnIdle {vm.saveExperimentalArchive(token) {raw->java.io.File(rule.activity.getExternalFilesDir(null),"wideband-history.json").writeText(raw)}}
        rule.waitUntil(5000) {vm.experimentalArchive?.saved==true};assertEquals(before,vm.session)
        rule.runOnIdle {vm.dismissMessage()}
        rule.onNodeWithText("選択した退避済み履歴を整理").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {vm.pruneExperimentalHistory(setOf(ids[0]))}
        assertEquals(2,vm.session.experiments.current()!!.comparisons.size)
        assertEquals(before.copy(experiments=vm.session.experiments),vm.session)
        rule.runOnIdle {vm.confirmMapping(false);vm.compareExperimental()};rule.waitUntil(15000) {!vm.autoBusy}
        val after=vm.session.experiments.current()!!;assertEquals(3,after.comparisons.size)
        assertEquals(c.id,after.id);assertEquals(c.coefficientHash,after.coefficientHash)
        assertFalse(ExperimentalRuns.id(after.comparisons.last()) in ids)
        assertEquals(before.copy(experiments=vm.session.experiments),vm.session);assertFalse(vm.audio.playing)
    }
    @Test fun widebandPresetIsExplicitAndPredictionTableRemainsAccessible() {
        init();val old=vm.session.autoFir
        rule.runOnIdle {vm.experimentalPage=false}
        assertEquals(old,vm.session.autoFir)
        rule.onNodeWithText("主試験帯域 300〜3,400 Hz").performScrollTo().performClick()
        assertEquals(300.0,vm.session.autoFir.lowHz,0.0);assertEquals(3400.0,vm.session.autoFir.highHz,0.0)
        rule.runOnIdle {vm.selectExperimentalData(diagnostic(true));vm.generateExperimental()};rule.waitUntil(15000) {!vm.autoBusy}
        assertTrue(vm.session.experiments.current()!!.playable)
        rule.onAllNodesWithText("固定サブバンドの予測")[0].performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("2000–3400")[0].performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
        val image=rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.getExternalFilesDir(null),"wideband-table.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
}
