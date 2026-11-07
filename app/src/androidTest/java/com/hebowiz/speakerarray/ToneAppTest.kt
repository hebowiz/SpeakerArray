package com.hebowiz.speakerarray

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Never run against a phone: the suite requires the explicit silent emulator opt-in. */
class ToneAppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun init() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("silentEmulator")=="true")
        rule.runOnIdle {vm.stop();vm.dismissMessage();vm.update {Session(orientationChosen=true,source=SourceSettings(frequency=750.0))};vm.tab=3}
        rule.waitUntil(10000) {!vm.preparing}
    }
    @Test fun independentMethodControlsAndImmediateCancelPreserveBothSlots() {
        init()
        fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
        }
        try {
        // Explicit landscape framebuffer, also on emulators whose rotation sensor is absent.
        shell("wm size 960x440");shell("wm density 160")
        Thread.sleep(1000);rule.waitForIdle()
        rule.waitUntil(10000) {rule.activity.resources.configuration.screenWidthDp>rule.activity.resources.configuration.screenHeightDp}
        rule.onNodeWithText("単一周波数",substring=false).performScrollTo().performClick()
        rule.waitUntil(10000) {vm.session.adjustmentMethod==AdjustmentMethod.TONE}
        rule.waitForIdle()
        val image=rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(rule.activity.getExternalFilesDir(null),"tone-ui.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        assertEquals(AdjustmentMethod.TONE,vm.session.adjustmentMethod)
        assertEquals(750.0,vm.session.autoToneHz,0.0)
        rule.onNodeWithText("対象周波数",substring=true).assertExists()
        rule.onNodeWithText("自動調整",substring=false).assertIsDisplayed()
        rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
        val before=vm.session
        rule.runOnIdle {vm.confirmMapping(false);vm.startAuto();vm.correction(CorrectionMode.SIMPLE);vm.cancelAuto("test cancel")}
        rule.waitUntil(10000) {!vm.autoBusy}
        assertEquals(before,vm.session)
        assertFalse(vm.maintainingRoute)
        assertTrue(vm.autoProgress.stage in listOf(AutoStage.CANCELLED,AutoStage.REJECTED))
        assertTrue(vm.autoDiagnostic.contains("TONE"))
        assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
        rule.runOnIdle {vm.dismissMessage()}
        rule.onNodeWithText("広帯域FIR",substring=false).performScrollTo().performClick()
        rule.waitUntil(10000) {vm.session.adjustmentMethod==AdjustmentMethod.FIR}
        assertEquals(before.processing,vm.session.processing)
        assertEquals(before.simple,vm.session.simple)
        } finally {shell("wm size reset");shell("wm density reset")}
    }
    @Test fun savedToneResultAndABNeverShowVerifiedAfterRouteReleaseOrRestart() {
        init()
        val s=Session(orientationChosen=true,source=SourceSettings(frequency=750.0),adjustmentMethod=AdjustmentMethod.TONE,
            simple=SimpleCorrection(sinePhaseDeg=-90.0,right=Channel(gainDb=-3.0)),correctionMode=CorrectionMode.SIMPLE)
        val profile=ToneProfile(s.simple,750.0,s.masterDb,s.swapped,s.autoFir,7,3,s.activeLayout,s.reverseLandscape,SessionRepository.encode(s),"{}")
        val saved=s.copy(toneProfile=profile).let {it.copy(correctionA=it.correctionSnapshot())}
        rule.runOnIdle {vm.loadSession(SessionRepository.encode(saved));vm.recallAB(false)}
        assertTrue(vm.calibrationStatus().contains("未検証"))
        assertEquals(saved.simple,vm.session.simple)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertFalse(vm.audio.playing);assertFalse(vm.maintainingRoute)
        assertTrue(vm.calibrationStatus().contains("未検証"))
    }
    @Test fun silentCaptureCannotApplyToneAndReleasesContinuousWorkers() {
        init()
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("pm grant com.hebowiz.speakerarray android.permission.RECORD_AUDIO").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        try {
            rule.runOnIdle {vm.adjustment(AdjustmentMethod.TONE);vm.confirmMapping(false)}
            val before=vm.session
            rule.runOnIdle {vm.startAuto()}
            rule.waitUntil(30000) {!vm.autoBusy}
            assertEquals(AutoStage.REJECTED,vm.autoProgress.stage)
            assertEquals(before,vm.session);assertNull(vm.session.toneProfile)
            assertFalse(vm.maintainingRoute)
            assertFalse(org.json.JSONObject(vm.autoDiagnostic).optBoolean("applicable"))
            assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
        } finally {
            // Permission revocation kills the instrumented process; the host revokes after the suite.
            rule.runOnIdle {vm.stop();vm.releaseHeldRoute()}
        }
    }
}
