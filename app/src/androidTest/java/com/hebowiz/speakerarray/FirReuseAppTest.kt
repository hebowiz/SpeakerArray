package com.hebowiz.speakerarray

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioFocusRequest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic accepted profiles only. No acoustic success is claimed. Run on the silent emulator. */
class FirReuseAppTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(rule.activity)[AppViewModel::class.java]
    private fun init() {
        check(InstrumentationRegistry.getArguments().getString("silentEmulator")=="true")
        rule.runOnIdle {vm.stop();vm.releaseHeldRoute();vm.dismissMessage();vm.tab=0}
        rule.waitUntil(5000) {!vm.engine.isRunning}
        Thread.sleep(300)
    }
    private fun fixture(settings: AutoFirSettings=AutoFirSettings(input=EndpointKey(7,"absent fixture MIC","fixture"))): Session {
        val h=List(257) {when(it) {127 -> 0.2;128 -> 0.6;129 -> -0.1;else -> 0.0}}
        val p=Processing(257,left=Channel(gainDb=-3.0,fir=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=h)),
            right=Channel(gainDb=-6.0,inverted=true),phaseDeg=-45.0,delayUs=125.0)
        val s=Session(processing=p,source=SourceSettings(frequency=750.0),masterDb=-60.0,
            orientationChosen=true,autoFir=settings,simple=SimpleCorrection(sinePhaseDeg=90.0,delayUs=250.0,right=Channel(gainDb=-12.0)))
        val profile=CalibrationProfile(123,settings,s.masterDb,s.swapped,vm.routes.manager.getStreamVolume(AudioManager.STREAM_MUSIC),
            vm.routes.manager.mode,false,p,SessionRepository.encode(s),"{\"fixture\":\"synthetic FIR, not an acoustic measurement\"}")
        return SessionRepository.decode(SessionRepository.encode(s.copy(calibration=profile).let {it.copy(correctionA=it.correctionSnapshot())}))
    }
    private fun load(s: Session) {
        rule.runOnIdle {vm.loadSession(SessionRepository.encode(s));vm.dismissMessage()}
        rule.waitUntil(10000) {!vm.preparing};Thread.sleep(300)
    }
    private fun play() {
        rule.runOnIdle {vm.start()}
        rule.waitUntil(15000) {vm.audio.playing && vm.audio.message=="再生"}
    }
    private fun stop() {
        rule.runOnIdle {vm.stop()};rule.waitUntil(5000) {!vm.engine.isRunning}
    }
    private fun noCapture(action: ()->Unit) {
        assertEquals(PackageManager.PERMISSION_DENIED,rule.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val r=AudioResourceUsage.recordingStarts.get();val c=AudioResourceUsage.communicationStarts.get()
        val mode=vm.routes.manager.mode
        action()
        assertEquals(r,AudioResourceUsage.recordingStarts.get());assertEquals(c,AudioResourceUsage.communicationStarts.get())
        assertEquals(mode,vm.routes.manager.mode)
        assertEquals(0,Thread.getAllStackTraces().keys.count {it.name.startsWith("AutoFIR-")})
    }
    @Test fun jsonPlaybackWithoutMicPreservesSlotsHistoryEditRestoreABAndUndo() {
        init();val s=fixture()
        noCapture {
            load(s);play()
            val worker=Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"}
            repeat(4) {i ->
                rule.runOnIdle {vm.correction(if(i%2==0) CorrectionMode.SIMPLE else CorrectionMode.FIR)}
                rule.waitUntil(10000) {vm.audio.generation==vm.generation && !vm.audio.switching}
            }
            assertSame(worker,Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"})
            assertEquals(s.processing,vm.session.effectiveProcessing());assertEquals(s.simple,vm.session.simple)
            assertTrue(vm.calibrationStatus().contains("未検証"));assertFalse(vm.maintainingRoute)
            rule.runOnIdle {vm.firProcessing {it.copy(phaseDeg=20.0)}}
            rule.waitUntil(10000) {vm.audio.generation==vm.generation && !vm.audio.switching}
            assertTrue(vm.calibrationStatus().contains("手動変更"));assertEquals(s.calibration,vm.session.calibration)
            rule.runOnIdle {vm.restoreMeasuredFir()};rule.waitUntil(5000) {!vm.engine.isRunning}
            rule.waitUntil(10000) {!vm.preparing};play();stop()
            assertEquals(s.processing,vm.session.processing)
            rule.runOnIdle {vm.recallAB(false);vm.showSavedFirResult()}
            assertEquals(s.processing,vm.session.processing);assertEquals(s.simple,vm.session.simple)
            assertTrue(vm.analysisJson.contains("synthetic FIR"))
            rule.runOnIdle {vm.reviewFirConditions()}
            assertFalse(vm.autoBusy);assertFalse(vm.engine.isRunning)
            assertTrue(vm.calibrationStatus().contains("未検証"))
            rule.runOnIdle {vm.undoAuto()}
            assertEquals(SessionRepository.decode(s.calibration!!.backup),vm.session)
        }
    }
    // Invoke alone, then force-stop from the host before invoking the restore test.
    @Test fun seedSavedFirForProcessRestart() {
        init();val s=fixture();load(s)
        rule.runOnIdle {vm.persist()};Thread.sleep(700)
        val prefs=rule.activity.getSharedPreferences("fir-reuse-test",0)
        assertTrue(prefs.edit().putInt("pid",android.os.Process.myPid()).putString("expected",SessionRepository.encode(vm.session)).commit())
        assertEquals(vm.session,SessionRepository.decode(rule.activity.getSharedPreferences("session",0).getString("last",null)!!))
    }
    @Test fun restoreSavedFirInNewProcessWithoutMic() {
        init()
        val prefs=rule.activity.getSharedPreferences("fir-reuse-test",0)
        assertNotEquals(prefs.getInt("pid",-1),android.os.Process.myPid())
        val expected=SessionRepository.decode(requireNotNull(prefs.getString("expected",null)))
        assertEquals(expected,vm.session);assertFalse(vm.maintainingRoute)
        rule.waitUntil(10000) {!vm.preparing}
        noCapture {play();assertTrue(vm.calibrationStatus().contains("未検証"));assertEquals(expected,vm.session);stop()}
    }
    @Test fun unsafeOrMissingOutputStopsAndDoesNotResume() {
        init();val s=fixture();load(s);play()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setOnAudioFocusChangeListener {}.build()
        vm.routes.manager.requestAudioFocus(focus)
        rule.waitUntil(5000) {!vm.engine.isRunning}
        vm.routes.manager.abandonAudioFocusRequest(focus);Thread.sleep(300);assertFalse(vm.engine.isRunning)
        load(s.copy(autoFir=s.autoFir.copy(output=EndpointKey(8,"missing output","fixture"))))
        rule.runOnIdle {vm.start()};assertFalse(vm.engine.isRunning);assertTrue(vm.message.isNotBlank())
        load(s);play()
        rule.runOnIdle {vm.firProcessing {it.copy(left=it.left.copy(fir=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(257) {Double.NaN})))}}
        assertEquals(s.processing,vm.session.processing);assertTrue(vm.message.contains("有限"))
        rule.runOnIdle {vm.update {it.copy(masterDb=0.0,processing=it.processing.copy(right=it.processing.right.copy(gainDb=12.0)))}}
        rule.waitUntil(10000) {!vm.engine.isRunning}
        assertTrue(vm.message.contains("ピーク") || vm.audio.message.contains("ピーク"))
        rule.runOnIdle {vm.start()};assertFalse(vm.engine.isRunning)
        load(s);assertFalse(vm.engine.isRunning)
        // A real input endpoint cannot silently become the requested media output.
        vm.engine.requestedOutput=vm.routes.resolve(null,true);vm.engine.settings=s
        vm.engine.start(PreparedProcessing.create(s.processing,999,true),null)
        rule.waitUntil(10000) {!vm.engine.isRunning}
        assertTrue(vm.audio.message.contains("出力"));assertFalse(vm.audio.playing)
    }
    @Test fun releasedMeasuredRouteUsesExactSavedFirThroughOrdinaryPlayback() = runBlocking<Unit> {
        init();grantCapture()
        val output=vm.routes.resolve(null,false);val input=vm.routes.resolve(null,true)
        val settings=AutoFirSettings(output=output.key(),input=input.key(),seconds=1.0)
        val port=MeasurementAudio(rule.activity,output,input,settings) {}
        try {
            withTimeout(15000) {port.open()};val s=fixture(settings);load(s);port.retain()
            rule.runOnIdle {vm.holdMeasurementRoute(port,fir=vm.session.calibration)}
            assertTrue(vm.calibrationStatus().contains("2信号"))
            rule.runOnIdle {vm.releaseHeldRoute()}
            rule.waitUntil(10000) {Thread.getAllStackTraces().keys.none {it.name.startsWith("AutoFIR-")}}
            Thread.sleep(300)
            val r=AudioResourceUsage.recordingStarts.get();val c=AudioResourceUsage.communicationStarts.get()
            play();assertEquals(s.processing,vm.session.processing);assertEquals(s.calibration,vm.session.calibration)
            assertTrue(vm.calibrationStatus().contains("未検証"));stop()
            assertEquals(r,AudioResourceUsage.recordingStarts.get());assertEquals(c,AudioResourceUsage.communicationStarts.get())
        } finally {rule.runOnIdle {vm.stop();vm.releaseHeldRoute()};port.close()}
    }
    @Test fun toneSuccessKeepsOldFirAndStreamButCannotCertifyIt() = runBlocking<Unit> {
        init();grantCapture()
        val output=vm.routes.resolve(null,false);val input=vm.routes.resolve(null,true)
        val settings=AutoFirSettings(output=output.key(),input=input.key(),seconds=1.0)
        val port=MeasurementAudio(rule.activity,output,input,settings) {}
        try {
            withTimeout(15000) {port.open()}
            val old=fixture(settings.copy(lowHz=500.0,highHz=1000.0))
            val point=TonePoint(-90.0,-3.0)
            val profile=ToneProfile(point.simple(TonePlan(750.0,settings.referenceRight)),750.0,old.masterDb,old.swapped,
                settings,port.volumeIndex,port.mode,old.activeLayout,old.reverseLandscape,SessionRepository.encode(old),"{\"fixture\":true}")
            val outcome=ToneOutcome(point,true,"synthetic only",emptyList(),List(2) {ToneVerification(4.0,4.0,0.0,0.0)})
            load(ToneTransaction.accept(old,outcome,profile));port.retain()
            rule.runOnIdle {vm.holdMeasurementRoute(port,tone=vm.session.toneProfile);vm.start()}
            rule.waitUntil(10000) {vm.audio.playing}
            val workers=Thread.getAllStackTraces().keys.filter {it.name.startsWith("AutoFIR-")}.toSet()
            val field=MeasurementAudio::class.java.getDeclaredField("track").apply {isAccessible=true}
            val track=field.get(port)
            repeat(5) {i ->
                rule.runOnIdle {vm.correction(if(i%2==0) CorrectionMode.FIR else CorrectionMode.SIMPLE)}
                rule.waitUntil(10000) {vm.audio.generation==vm.generation && !vm.audio.switching}
                assertTrue(vm.engine.isRunning);assertTrue(vm.maintainingRoute)
                assertSame(track,field.get(port));assertEquals(workers,Thread.getAllStackTraces().keys.filter {it.name.startsWith("AutoFIR-")}.toSet())
            }
            assertEquals(old.processing,vm.session.effectiveProcessing());assertEquals(profile.simple,vm.session.simple)
            assertEquals(old.calibration,vm.session.calibration);assertTrue(vm.calibrationStatus().contains("未検証"))
            assertTrue(vm.calibrationStatus().contains("このFIRの検証経路ではありません"))
            rule.runOnIdle {vm.firProcessing {it.copy(phaseDeg=30.0)}}
            rule.waitUntil(10000) {vm.audio.generation==vm.generation && !vm.audio.switching}
            assertTrue(vm.maintainingRoute);assertSame(track,field.get(port))
            assertTrue(vm.calibrationStatus().contains("手動変更"))
            rule.runOnIdle {vm.restoreMeasuredFir()};rule.waitUntil(5000) {!vm.engine.isRunning}
            rule.waitUntil(10000) {!vm.preparing}
            rule.runOnIdle {vm.start()};rule.waitUntil(10000) {vm.audio.playing}
            assertTrue(vm.maintainingRoute);assertSame(track,field.get(port))
            assertEquals(old.processing,vm.session.processing);assertTrue(vm.calibrationStatus().contains("未検証"))
            // A dead retained stream must stop, never migrate to ordinary playback on its own.
            port.close();rule.waitUntil(10000) {!vm.engine.isRunning && !vm.maintainingRoute}
            Thread.sleep(400);assertFalse(vm.audio.playing);assertEquals(old.calibration,vm.session.calibration)
        } finally {rule.runOnIdle {vm.stop();vm.releaseHeldRoute()};port.close()}
    }
    private fun grantCapture() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.hebowiz.speakerarray android.permission.RECORD_AUDIO").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }
}
