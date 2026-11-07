package com.hebowiz.speakerarray

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioImportAppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun fixture(name: String): Uri {
        val file=File(rule.activity.filesDir,"import-test-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/$name").use {src->file.outputStream().use {src.copyTo(it)}}
        return Uri.fromFile(file)
    }
    @Test fun importsRestoreAndPlayThroughExistingDspWhileFailuresKeepPreviousSource() {
        check(InstrumentationRegistry.getArguments().getString("silentEmulator")=="true")
        val flac=fixture("tone.flac");val mp3=fixture("tone.mp3")
        rule.runOnIdle {vm.stop();vm.loadSession(SessionRepository.encode(Session(orientationChosen=true,masterDb=-60.0,
            source=SourceSettings(kind=SourceKind.WAV,loop=true))));vm.dismissMessage();vm.loadAudio(flac,false)}
        rule.waitUntil(15000) {!vm.loading && !vm.preparing}
        assertEquals(flac.toString(),vm.session.source.wavUri);assertFalse(vm.audio.playing)
        val saved=SessionRepository.encode(vm.session)
        rule.runOnIdle {vm.loadSession(saved)}
        rule.waitUntil(15000) {!vm.loading && !vm.preparing}
        assertEquals(flac.toString(),vm.session.source.wavUri)
        rule.runOnIdle {vm.source {it.copy(mono=MonoMode.RIGHT)}}
        rule.waitUntil(15000) {!vm.loading}
        assertEquals(MonoMode.RIGHT,vm.session.source.mono)
        val original=vm.session.source
        val bad=File(rule.activity.filesDir,"bad-audio.mp3").apply {writeText("invalid")}
        rule.runOnIdle {vm.loadAudio(Uri.fromFile(bad),false)}
        rule.waitUntil(15000) {!vm.loading}
        assertEquals(original,vm.session.source);assertTrue(vm.message.contains("読み込めません"))
        rule.runOnIdle {vm.dismissMessage();vm.loadAudio(flac,false,MonoMode.LEFT);vm.loadAudio(mp3,false,MonoMode.AVERAGE)}
        rule.waitUntil(15000) {!vm.loading && !vm.preparing}
        assertEquals(mp3.toString(),vm.session.source.wavUri);assertEquals(MonoMode.AVERAGE,vm.session.source.mono)
        assertTrue(rule.activity.cacheDir.listFiles()!!.none {it.name.startsWith("audio-import-") || it.name.startsWith("audio-decoded-")})
        rule.waitUntil(5000) {rule.activity.cacheDir.listFiles()!!.count {it.name.startsWith("wav-48000")}==1}
        try {
            rule.runOnIdle {vm.dismissMessage()}
            rule.onNodeWithText("音声ファイルを選択").performScrollTo().assertIsDisplayed()
            rule.runOnIdle {vm.start()}
            rule.waitUntil(10000) {vm.audio.playing && vm.audio.message=="再生"}
            rule.runOnIdle {vm.engine.seek(.6)}
            rule.waitUntil(5000) {vm.audio.position>.5}
            Thread.sleep(1300);assertTrue(vm.audio.playing);assertEquals(0,vm.audio.clips)
        } finally {rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.engine.isRunning}}
    }
}
