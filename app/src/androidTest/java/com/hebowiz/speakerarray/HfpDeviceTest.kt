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
                vm.autoSettings {it.copy(output=output.key(),input=inputs.single().key(),preset=args.getString("preset")?.toIntOrNull() ?: 6,highHz=3400.0)}
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
