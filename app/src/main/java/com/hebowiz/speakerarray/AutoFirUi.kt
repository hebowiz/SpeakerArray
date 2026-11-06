@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.hebowiz.speakerarray

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebowiz.speakerarray.core.*
import kotlin.math.*

@Composable private fun <T> Choice(label: String, value: T, options: List<Pair<String,T>>, enabled: Boolean, change: (T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull {it.second==value}?.first ?: "未接続の選択端点"}",maxLines=3)
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            options.forEach { (text,item)->DropdownMenuItem(text={Text(text)},onClick={expanded=false;change(item)}) }
        }
    }
}

@Composable fun AutoFirPage(vm: AppViewModel, export: (String)->Unit) {
    val context=LocalContext.current
    var permissionsRevision by remember {mutableIntStateOf(0)}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {permissionsRevision++}
    val required=remember {listOf(Manifest.permission.RECORD_AUDIO)+if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()}
    val permitted=remember(permissionsRevision) {required.all {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}}
    val inputs=remember(vm.routeRevision,permissionsRevision) {vm.routes.devices(true)}
    val outputs=remember(vm.routeRevision,permissionsRevision) {vm.routes.devices(false)}
    val s=vm.session.autoFir;val busy=vm.autoBusy
    var advanced by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(horizontal=8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=vm::startAuto,enabled=!busy && permitted && !vm.loading) {Text("測定・設計・適用")}
            OutlinedButton(onClick={vm.cancelAuto()},enabled=busy) {Text("取消")}
            if(!permitted) OutlinedButton(onClick={permissions.launch(required.toTypedArray())},enabled=!busy) {Text("MIC・Bluetooth権限を許可")}
            Text("${vm.autoProgress.stage.label}  ${(vm.autoProgress.fraction*100).toInt()}%",modifier=Modifier.padding(vertical=12.dp))
        }
        if(busy) LinearProgressIndicator(progress={vm.autoProgress.fraction},modifier=Modifier.fillMaxWidth())
        val live=busy || vm.maintainingRoute
        val actualOutput=if(live) vm.measurementRoute.actual.substringBefore(" / MIC ").removePrefix("出力 ") else "未確認（停止中）"
        val actualInput=if(live) vm.measurementRoute.actual.substringAfter(" / MIC ","未確認") else "未確認（停止中）"
        Text("出力: 要求 ${s.output?.let {"${it.name} / type=${it.type}"} ?: "自動（内蔵）"} → 実 $actualOutput",fontSize=11.sp,maxLines=2)
        Text("MIC: 要求 ${s.input?.let {"${it.name} / type=${it.type}"} ?: "自動（内蔵）"} → 実 $actualInput",fontSize=11.sp,maxLines=2)
        BoxWithConstraints(Modifier.weight(1f)) {
            val controls: @Composable ()->Unit = {
                Choice("音声出力",s.output,listOf("自動（内蔵）" to null)+outputs.map {it.description() to it.key()},!busy) {key ->vm.autoSettings {it.copy(output=key)}}
                Choice("MIC入力",s.input,listOf("自動（内蔵）" to null)+inputs.map {it.description() to it.key()},!busy) {key ->
                    vm.autoSettings {it.copy(input=key,highHz=if(key?.type==android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 3400.0 else min(8000.0,it.inputRate*0.44))}
                }
                Text(vm.measurementRoute.pcm,fontSize=12.sp)
                if(!busy) {
                    Choice("基準ch",s.referenceRight,listOf("左基準・右補正" to false,"右基準・左補正" to true),true) {v->vm.autoSettings {it.copy(referenceRight=v)}}
                    NumberControl("測定下限",s.lowHz,"Hz",80.0..10000.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(lowHz=v)}}
                    NumberControl("測定上限",s.highHz,"Hz",100.0..16000.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(highHz=v)}}
                    NumberControl("測定レベル（共通出力）",vm.session.masterDb,"dB",-60.0..0.0,vm::stop,slider=false) {v->vm.update {it.copy(masterDb=v)}}
                    Choice("FIR長",s.taps,listOf(257,513,1025).map {"$it taps" to it},true) {v->vm.autoSettings {it.copy(taps=v)}}
                    TextButton(onClick={advanced=!advanced}) {Text("高度な設定")}
                    if(advanced) {
                        Choice("録音プリセット",s.preset,listOf("VOICE_RECOGNITION" to 6,"VOICE_COMMUNICATION" to 7,"UNPROCESSED" to 9),true) {v->vm.autoSettings {it.copy(preset=v)}}
                        Choice("入力PCM",s.inputRate,listOf(8000,16000,32000,44100,48000).map {"$it Hz mono" to it},true) {v->vm.autoSettings {it.copy(inputRate=v,highHz=min(it.highHz,v*0.44))}}
                        Choice("測定信号",s.signal,listOf("対数スイープ" to ProbeKind.SWEEP,"位相既知マルチトーン" to ProbeKind.MULTITONE),true) {v->vm.autoSettings {it.copy(signal=v)}}
                        NumberControl("各スイープ",s.seconds,"秒",1.0..5.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(seconds=v)}}
                        NumberControl("残響保持",s.tailSeconds,"秒",0.1..2.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(tailSeconds=v)}}
                        NumberControl("同期探索余裕",s.latencySeconds,"秒",0.2..3.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(latencySeconds=v)}}
                        NumberControl("正則化",s.regularization,"",0.0001..1.0,vm::stop,slider=false,digits=4) {v->vm.autoSettings {it.copy(regularization=v)}}
                        NumberControl("最大ブースト",s.maxBoostDb,"dB",0.0..12.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(maxBoostDb=v)}}
                    }
                } else Text("測定中は音源・処理・ルートの変更をロックしています。")
            }
            val results: @Composable ()->Unit = {
                Text(vm.autoProgress.detail, fontSize=16.sp)
                Text("MIC ${fmt(vm.measurementRoute.inputDb)} dBFS / クリップ ${vm.measurementRoute.clips}")
                Text(vm.measurementRoute.effects,fontSize=12.sp)
                if(vm.maintainingRoute) {
                    Text("校正条件を維持するためMIC録音・HFP通信経路を維持中（音声モニターなし）",color=RightOrange)
                    OutlinedButton(onClick={vm.stop();vm.releaseHeldRoute()}) {Text("MIC・通信経路を解放")}
                }
                Text(vm.calibrationStatus(),fontSize=13.sp)
                FlowRow {
                    OutlinedButton(onClick=vm::undoAuto,enabled=!busy && vm.session.calibration!=null) {Text("元に戻す")}
                    OutlinedButton(onClick={export(vm.exportAutoResult())},enabled=!busy && (vm.session.calibration!=null || vm.autoDiagnostic.isNotEmpty())) {Text("結果保存")}
                }
                val result=vm.autoResult
                if(result!=null) {
                    Text("有効ビン ${(result.pair.coverage*100).toInt()}% / ${fmt(s.lowHz,0)}–${fmt(s.highHz,0)} Hz内。重み0の帯域は評価対象外。")
                    Text("反復差 ${fmt(result.pair.repeatDb)} dB / ${fmt(result.pair.repeatPhase)}°\nクロック差 ${fmt(result.timing.ppm)} ppm / 同期残差 ${fmt(result.timing.residual,3)} sample")
                    result.verification.forEachIndexed {i,v->Text("検証${i+1}: 片側比 ${fmt(v.reductionSingleDb)} dB / 未補正両側比 ${fmt(v.reductionBothDb)} dB${if(v.noiseLimited) "（測定限界・ノイズ床）" else ""}")}
                    Text("有限長FIR予測残差 ${fmt(result.solution.predictedDb)} dB / 最大ブースト ${fmt(result.solution.maxBoostDb)} dB")
                    val bins=result.pair.weights.indices.filter {it*SAMPLE_RATE.toDouble()/TransferEstimator.SIZE in s.lowHz..s.highHz}
                    fun values(f: (Int)->Double)=DoubleArray(bins.size) {f(bins[it])}
                    Chart(values {levelDb(result.pair.right.magnitude(it))},values {levelDb(result.pair.left.magnitude(it))},null,null,null,"${fmt(s.lowHz,0)}–${fmt(s.highHz,0)} Hz / 青=左 緑=右","測定応答 dB")
                    Chart(values {wrapPhase((atan2(result.pair.right.im[it],result.pair.right.re[it])-atan2(result.pair.left.im[it],result.pair.left.re[it]))*180/PI)},null,null,-180.0,180.0,"同じ共通時刻基準の右−左","相対位相 °")
                    Chart(values {levelDb(result.corrected.magnitude(it))},values {levelDb(result.both.magnitude(it))},values {levelDb(result.single.magnitude(it))},null,null,"青=未補正両側 緑=補正後 点線=片側基準","MIC録音応答 dB")
                    val verification=result.verification.last()
                    Chart(verification.bandReduction.toDoubleArray(),null,null,null,null,"低域→高域 / 1/3オクターブ","低減量 dB")
                    Text("設計した左右FIRの応答と係数は「FIR編集」で確認できます。")
                }
                Text("1測定点でのMIC録音上の相対低減量です。未校正の音圧dB SPLではありません。MICを固定して左右を確認してください。HFP内部のAGC・NS等は解除できるとは限りません。",fontSize=12.sp)
                Text("合格基準: SNR 20 dB、反復差1 dB・5°以内、有効ビン80%以上。片側／両側比とも3 dB以上低減を2回確認。帯域別悪化は3 dB以内。",fontSize=12.sp)
            }
            if(maxWidth>=650.dp) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState())) {controls()}
                Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) {results()}
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {controls();results()}
        }
    }
}
