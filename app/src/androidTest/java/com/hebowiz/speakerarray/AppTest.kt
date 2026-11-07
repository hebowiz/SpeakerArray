package com.hebowiz.speakerarray

import android.graphics.Bitmap
import android.util.Log
import android.media.AudioManager
import android.media.AudioFocusRequest
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun init() {
        rule.waitForIdle()
        if(rule.onAllNodesWithText("通常の横置き").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("通常の横置き").performClick()
        rule.runOnIdle {vm.dismissMessage();vm.update {Session(orientationChosen=true)};vm.selectSide(false)}
        rule.waitUntil(10000) {vm.draftCurve!=null}
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes()}
    }
    @Test fun autoFirPermissionFailureAndImmediateCancelPreserveSettings() {
        init()
        rule.runOnIdle {
            val p=Processing(length=257,phaseDeg=90.0,delayUs=123.0,left=Channel(gainDb=-6.0))
            vm.update {it.copy(processing=p,a=p,b=p.copy(phaseDeg=-90.0))}
            vm.confirmMapping(false)
        }
        val backup=vm.session
        rule.runOnIdle {vm.startAuto()}
        rule.waitUntil(10000) {!vm.autoBusy}
        assertEquals(AutoStage.REJECTED,vm.autoProgress.stage)
        assertEquals(backup,vm.session)
        assertTrue(vm.autoDiagnostic.contains("REJECTED"))
        rule.runOnIdle {vm.confirmMapping(false);vm.startAuto();vm.stop()}
        rule.waitUntil(10000) {!vm.autoBusy}
        // Permission rejection may win the race with Stop; either terminal state must release ownership.
        assertTrue(vm.autoProgress.stage in listOf(AutoStage.CANCELLED,AutoStage.REJECTED))
        assertEquals(backup,vm.session)
        assertFalse(vm.audio.playing)
        assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
    }
    @Test fun responsiveScreensKeepStopVisible() {
        init()
        try {
            shell("wm density 160")
            for((width,height) in listOf(700 to 320,800 to 360,960 to 440)) {
                shell("wm size ${height}x${width}")
                Thread.sleep(1500) // Window manager rotation/resize animation is outside Compose idling.
                rule.waitForIdle()
                Log.i("SpeakerArrayValidation","requested ${width}x${height}dp, actual ${rule.activity.resources.configuration.screenWidthDp}x${rule.activity.resources.configuration.screenHeightDp}dp, density=${rule.activity.resources.displayMetrics.density}")
                for(tab in listOf("再生・調整","FIR編集","理論プレビュー","フィルタ自動設計")) {
                    rule.onNodeWithText(tab).performScrollTo().performClick()
                    rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
                    if(tab=="フィルタ自動設計") {
                        rule.onNodeWithText("自動調整").assertIsDisplayed()
                        rule.onNodeWithText("取消").assertIsDisplayed()
                    }
                    Thread.sleep(350)
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val folder=File(rule.activity.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
                    File(folder,"${width}x${height}-${tab}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
            }
            shell("settings put system font_scale 1.3")
            Thread.sleep(1000)
            rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            rule.onNodeWithContentDescription("常設の停止").assertIsDisplayed()
            assertFalse(vm.audio.playing)
        } finally {
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")
        }
    }
    @Test fun firGestureManualApplyAndLinkedState() {
        init()
        rule.runOnIdle {vm.automatic(false);vm.tab=1}
        rule.onNodeWithText("グラフィックEQ").performScrollTo().performClick()
        rule.waitUntil(10000) {vm.draft.mode==FirMode.EQ && !vm.designing}
        rule.onNodeWithContentDescription("dB グラフ").performScrollTo().performTouchInput {
            swipe(Offset(width*0.57f,height*0.5f),Offset(width*0.57f,height*0.2f),500)
        }
        rule.waitUntil(10000) {vm.firPending && !vm.designing}
        assertTrue(vm.draft.eq.any {it!=0.0})
        assertEquals(FirMode.FLAT,vm.session.processing.left.fir.mode)
        rule.onNodeWithText("反映",substring=false).performScrollTo().performClick()
        rule.waitUntil(10000) {vm.session.processing.left.fir.mode==FirMode.EQ}
        val right=vm.session.processing.right
        rule.runOnIdle {vm.linked=true}
        assertEquals(right,vm.session.processing.right)
        rule.runOnIdle {vm.editFir {it.copy(eq=List(10) {3.0})};vm.applyFir()}
        assertEquals(vm.session.processing.left.fir,vm.session.processing.right.fir)
        rule.runOnIdle {vm.automatic(true);vm.editFir {it.copy(eq=List(10) {-2.0})}}
        rule.waitUntil(10000) {vm.session.processing.left.fir.eq.all {it == -2.0}}
        rule.runOnIdle {vm.editFir {it.copy(eq=List(10) {8.0})};vm.automatic(false)}
        rule.waitUntil(10000) {!vm.designing}
        assertTrue(vm.session.processing.left.fir.eq.all {it == -2.0})
    }
    @Test fun playbackUpdatesRecreationAndBackgroundStop() {
        init()
        rule.runOnIdle {vm.length(1025)}
        rule.waitUntil(10000) {vm.activeCurve!=null && vm.session.processing.length==1025}
        Thread.sleep(300)
        rule.runOnIdle {vm.start()}
        rule.waitUntil(15000) {vm.audio.playing && vm.audio.message=="再生"}
        val before=Thread.getAllStackTraces().keys.count {it.name=="SpeakerArray-audio"}
        assertEquals(1,before)
        repeat(10) {i ->
            rule.runOnIdle {vm.processing {it.copy(delayUs=if(i%2==0) 437.0 else -437.0)};vm.tab=i%3}
            Thread.sleep(100)
        }
        rule.waitUntil(15000) {vm.audio.generation==vm.generation && !vm.audio.switching}
        Log.i("SpeakerArrayValidation","1025 taps: CPU max block=${vm.audio.cpuPercent}%, underruns=${vm.audio.underruns}, clips=${vm.audio.clips}")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(1,Thread.getAllStackTraces().keys.count {it.name=="SpeakerArray-audio"})
        val retained=vm
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(15000) {!retained.audio.playing}
        assertEquals(0,Thread.getAllStackTraces().keys.count {it.name=="SpeakerArray-audio"})
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle();assertFalse(vm.audio.playing)
    }
    @Test fun numericKeyboardHasStopAndFocusLossDoesNotResume() {
        init();Thread.sleep(300)
        rule.runOnIdle {vm.start()}
        rule.waitUntil(15000) {vm.audio.playing && vm.audio.message=="再生"}
        rule.onNodeWithText("共通出力",substring=true).performClick()
        rule.onNode(hasSetTextAction()).performClick().performTextReplacement("-30")
        rule.onNode(hasText("停止") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        rule.waitUntil(15000) {!vm.audio.playing}
        rule.onNodeWithText("反映").performClick()
        assertEquals(-30.0,vm.session.masterDb,0.0)
        rule.runOnIdle {vm.start()}
        rule.waitUntil(15000) {vm.audio.playing && vm.audio.message=="再生"}
        val manager=rule.activity.getSystemService(AudioManager::class.java)
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setOnAudioFocusChangeListener {}.build()
        manager.requestAudioFocus(focus)
        rule.waitUntil(15000) {!vm.audio.playing}
        manager.abandonAudioFocusRequest(focus)
        Thread.sleep(300);assertFalse(vm.audio.playing)
    }
}
