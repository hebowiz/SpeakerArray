package com.hebowiz.speakerarray

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Explicit opt-in only: never run acoustic hardware checks in the ordinary regression suite. */
@RunWith(AndroidJUnit4::class)
class HfpDeviceTest {
    @Test fun authorizedBuiltinCorrectionSwitch() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("correctionPlayback")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val trace=StringBuilder()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var vm: AppViewModel
            lateinit var original: Session
            scenario.onActivity {activity ->
                vm=ViewModelProvider(activity)[AppViewModel::class.java];original=vm.session
                check(!vm.autoBusy && !vm.maintainingRoute)
                check(vm.routes.resolve(original.autoFir.output,false).type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                vm.stop();vm.dismissMessage();vm.tab=0
            }
            val volume=context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            try {
                // A known bounded comparison fixture. Restored in full, including calibration and both slots.
                scenario.onActivity {vm.update {it.copy(masterDb=-24.0,calibration=null,correctionMode=CorrectionMode.FIR,
                    source=it.source.copy(kind=SourceKind.SINE),processing=Processing(length=it.processing.length,right=Channel(gainDb=-6.0)),
                    simple=SimpleCorrection(right=Channel(gainDb=-6.0),sinePhaseDeg=-90.0,referenceHz=it.source.frequency))}}
                delay(800);scenario.onActivity {vm.start()}
                withTimeout(8000) {while(!vm.audio.playing || !vm.audio.route.contains("type=2")) delay(50)}
                val thread=Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"}
                val route=vm.audio.route
                repeat(5) {i ->
                    scenario.onActivity {vm.correction(if(i%2==0) CorrectionMode.SIMPLE else CorrectionMode.FIR)}
                    delay(3200)
                    check(vm.audio.playing && vm.audio.generation==vm.generation && !vm.audio.switching) {vm.audio.message}
                    trace.appendLine("expectedRoute=$route, actualRoute=${vm.audio.route}, clips=${vm.audio.clips}, mode=${vm.session.correctionMode}")
                    check(vm.audio.clips==0L) {"clips=${vm.audio.clips}"};check(vm.audio.route==route) {"expected $route, actual ${vm.audio.route}"}
                    check(Thread.getAllStackTraces().keys.single {it.name=="SpeakerArray-audio"}===thread)
                    trace.appendLine("mode=${vm.session.correctionMode}, applied=${vm.appliedMode}, route=$route, generation=${vm.audio.generation}, clips=${vm.audio.clips}, underruns=${vm.audio.underruns}")
                }
                check(context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)==volume)
                val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null),"correction-switch.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            } finally {
                scenario.onActivity {vm.stop()}
                withContext(NonCancellable) {
                    withTimeout(5000) {while(vm.engine.isRunning) delay(30)}
                    scenario.onActivity {vm.update {original};vm.persist()}
                    delay(600)
                    val prefs=context.getSharedPreferences("session",android.content.Context.MODE_PRIVATE)
                    check(SessionRepository.decode(requireNotNull(prefs.getString("last",null)))==original)
                    check(prefs.edit().commit())
                    File(context.getExternalFilesDir(null),"correction-switch.txt").writeText(trace.toString())
                }
            }
        }
    }
    @Test fun showSavedDiagnosticWithoutAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("showDiagnostic")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity ->
                val vm=ViewModelProvider(activity)[AppViewModel::class.java]
                vm.stop();vm.dismissMessage();vm.tab=3
                assertFalse(vm.autoBusy)
                val saved=org.json.JSONObject(vm.exportAutoResult())
                assertTrue(saved.has("reasonCode"))
                assertFalse(vm.maintainingRoute)
            }
            Thread.sleep(1500)
            val image=instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null),"practical-diagnostic-screen.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
    }
    @Test fun runAuthorizedAcousticMeasurement() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("acousticMeasurement")=="true" && args.getString("physicalMappingConfirmed")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val trace=StringBuilder()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var vm: AppViewModel
            var originalMaster=-24.0
            scenario.onActivity {activity->
                vm=ViewModelProvider(activity)[AppViewModel::class.java]
                originalMaster=vm.session.masterDb
                val output=vm.routes.resolve(null,false)
                val inputs=vm.routes.devices(true).filter {it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO}
                require(inputs.size==1)
                vm.dismissMessage()
                vm.autoSettings {it.copy(output=output.key(),input=inputs.single().key(),preset=args.getString("preset")?.toIntOrNull() ?: 6,
                    lowHz=args.getString("lowHz")?.toDoubleOrNull() ?: 300.0,highHz=args.getString("highHz")?.toDoubleOrNull() ?: 3400.0)}
                vm.confirmMapping(vm.session.swapped)
                args.getString("masterDb")?.toDoubleOrNull()?.let {level ->vm.update {it.copy(masterDb=level)}}
                vm.tab=3;vm.startAuto()
            }
            var last=""
            try {
                withTimeout(240000) {
                    while(true) {
                        var busy=false
                        scenario.onActivity {
                            busy=vm.autoBusy
                            val status="${vm.autoProgress} / ${vm.measurementRoute}"
                            if(last!=status) {trace.appendLine(status);last=status}
                        }
                        if(!busy) break
                        delay(1000)
                    }
                }
                scenario.onActivity {
                    trace.appendLine("FINAL=${vm.autoProgress}")
                    vm.session.calibration?.let {File(context.getExternalFilesDir(null),"autofir-profile.json").writeText(CalibrationRepository.profile(it).toString(2))}
                    File(context.getExternalFilesDir(null),"autofir-device-session.json").writeText(SessionRepository.encode(vm.session))
                    File(context.getExternalFilesDir(null),"autofir-diagnostic.json").writeText(vm.autoDiagnostic)
                }
            } finally {
                withContext(NonCancellable) {
                    scenario.onActivity {vm.cancelAuto("試験終了");vm.stop();vm.releaseHeldRoute()}
                    withTimeout(10000) {
                        var busy=true
                        while(busy) {scenario.onActivity {busy=vm.autoBusy};delay(50)}
                    }
                    scenario.onActivity {vm.update {it.copy(masterDb=originalMaster)}}
                    awaitSavedMaster(originalMaster)
                }
                File(context.getExternalFilesDir(null),"autofir-acoustic-check.txt").writeText(trace.toString())
            }
        }
    }
    @Test fun restoreAuthorizedComparisonMaster() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("restoreComparisonMaster")=="true")
        val level=requireNotNull(args.getString("masterDb")).toDouble()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity ->
                val vm=ViewModelProvider(activity)[AppViewModel::class.java]
                check(!vm.autoBusy)
                vm.stop();vm.update {it.copy(masterDb=level)}
            }
            awaitSavedMaster(level)
        }
    }
    private suspend fun awaitSavedMaster(level: Double) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("session",android.content.Context.MODE_PRIVATE)
        withTimeout(5000) {
            while(prefs.getString("last",null)?.let {SessionRepository.decode(it).masterDb} != level) delay(50)
        }
        // Flush pending SharedPreferences disk writes before ActivityScenario destroys the ViewModel.
        assertTrue(prefs.edit().commit())
    }
    @Test fun inspectHfpRoutesWithSilence() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("hardwareAudio")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(AudioManager::class.java)
        val beforeMode=manager.mode
        val beforeVolume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val reports=StringBuilder()
        ActivityScenario.launch(MainActivity::class.java).use {
            val router=DeviceRoutes(context) {}
            try {
                val output=router.resolve(null,false)
                val candidates=router.devices(true).filter {it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO}
                reports.appendLine("outputs=${router.devices(false).map {it.description()}}")
                reports.appendLine("inputs=${router.devices(true).map {it.description()}}")
                require(candidates.size==1) {"HFP入力を一意に選択できません"}
                for(preset in listOf(6,7)) {
                    var state=MeasurementRouteState()
                    val config=AutoFirSettings(output=output.key(),input=candidates.single().key(),preset=preset,highHz=3400.0)
                    val io=MeasurementAudio(context,output,candidates.single(),config) {state=it}
                    try {
                        withTimeout(15000) {io.open();io.silence(1.0)}
                        reports.appendLine("preset=$preset ROUTE_MATCHED (silent PCM only; acoustic stereo unverified)")
                    } catch(e: Exception) {reports.appendLine("preset=$preset NOT_CONFIRMED: ${e.message}")}
                    finally {state=io.state();io.close();reports.appendLine(state.toString())}
                    assertEquals(beforeMode,manager.mode)
                    reports.appendLine("volume API before=$beforeVolume after=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} (route-dependent; no setter called)")
                }
            } finally {
                router.close()
                val file=File(context.getExternalFilesDir(null),"autofir-route-check.txt")
                file.writeText(reports.toString())
                android.util.Log.i("AutoFirHardware",reports.toString())
            }
        }
    }
}
