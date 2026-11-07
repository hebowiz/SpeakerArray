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

@Composable internal fun <T> Choice(label: String, value: T, options: List<Pair<String,T>>, enabled: Boolean, blocked: (T)->String? = {null}, change: (T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull {it.second==value}?.first ?: "未接続の選択端点"}",maxLines=3)
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            options.forEach { (text,item)->DropdownMenuItem(text={Text(text+(blocked(item)?.let {"\n$it"} ?: ""))},enabled=blocked(item)==null,onClick={expanded=false;change(item)}) }
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
    val s=vm.session.autoFir;val busy=vm.autoBusy
    val tone=vm.session.adjustmentMethod==AdjustmentMethod.TONE
    val blockReason=vm.measurementBlockReason()
    val diagnostic=remember(vm.analysisJson) {runCatching {org.json.JSONObject(vm.analysisJson)}.getOrNull()}
    var advanced by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(horizontal=8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=vm::startAuto,enabled=!busy && permitted && !vm.loading && blockReason==null) {Text("自動調整")}
            OutlinedButton(onClick={vm.cancelAuto()},enabled=busy) {Text("取消")}
            if(!permitted) OutlinedButton(onClick={permissions.launch(required.toTypedArray())},enabled=!busy) {Text("MIC・Bluetooth権限を許可")}
            Text("${vm.autoProgress.stage.label}  ${(vm.autoProgress.fraction*100).toInt()}%",modifier=Modifier.padding(vertical=12.dp))
        }
        Text("選択方式: ${if(vm.session.correctionMode==CorrectionMode.FIR) "FIR" else "位相・振幅"} / 自動調整成功時のみ選択した調整方式へ反映",fontSize=12.sp)
        if(blockReason!=null) Text(blockReason,color=RightOrange,fontSize=12.sp)
        if(busy) LinearProgressIndicator(progress={vm.autoProgress.fraction},modifier=Modifier.fillMaxWidth())
        val live=busy || vm.maintainingRoute
        val actualOutput=if(live) vm.measurementRoute.actual.substringBefore(" / MIC ").removePrefix("出力 ") else "未確認（停止中）"
        val actualInput=if(live) vm.measurementRoute.actual.substringAfter(" / MIC ","未確認") else "未確認（停止中）"
        Text("出力: 要求 ${s.output?.let {"${it.name} / type=${it.type}"} ?: "自動（内蔵）"} → 実 $actualOutput",fontSize=11.sp,maxLines=2)
        Text("MIC: 要求 ${s.input?.let {"${it.name} / type=${it.type}"} ?: "自動（内蔵）"} → 実 $actualInput",fontSize=11.sp,maxLines=2)
        if(vm.maintainingRoute) Text("MIC・測定出力・通信条件を維持中 / 停止・解放は結果欄",color=RightOrange,fontSize=11.sp)
        BoxWithConstraints(Modifier.weight(1f)) {
            val controls: @Composable ()->Unit = {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for((name,method) in listOf("単一周波数" to AdjustmentMethod.TONE,"広帯域FIR" to AdjustmentMethod.FIR))
                        FilterChip(selected=vm.session.adjustmentMethod==method,onClick={vm.adjustment(method)},enabled=!busy,label={Text(name)})
                }
                OutputSelector(vm)
                Choice("MIC入力",s.input,listOf("自動（内蔵）" to null)+inputs.map {it.description() to it.key()},!busy,blocked={key ->runCatching {
                    RoutePolicy.reason(vm.routes.resolve(s.output,false).type,vm.routes.resolve(key,true).type)
                }.getOrElse {it.message}}) {key ->
                    vm.autoSettings {it.copy(input=key,highHz=if(key?.type==android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 3400.0 else min(8000.0,it.inputRate*0.44))}
                }
                Text(vm.measurementRoute.pcm,fontSize=12.sp)
                if(!busy) {
                    Choice("基準ch",s.referenceRight,listOf("左基準・右補正" to false,"右基準・左補正" to true),true) {v->vm.autoSettings {it.copy(referenceRight=v)}}
                    if(tone) {
                        NumberControl("対象周波数",vm.session.autoToneHz,"Hz",80.0..min(16000.0,s.inputRate*0.44),vm::stop,slider=false) {v->vm.update {it.copy(autoToneHz=v)}}
                        val plan=TonePlan(vm.session.autoToneHz,s.referenceRight)
                        Text("補正ch −6〜+6 dB / 位相30°粗探索→5°微調整 / ゲイン2 dB→0.5 dB。基準chは0 dB固定。")
                        Text("概算 ${plan.estimatedSeconds}秒〜最大${plan.maxSeconds}秒。実測遅延・整定に応じて延長。最大${plan.maxMeasurements}評価、各0.5秒×3窓。",fontSize=12.sp)
                    } else {
                    NumberControl("測定下限",s.lowHz,"Hz",80.0..10000.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(lowHz=v)}}
                    NumberControl("測定上限",s.highHz,"Hz",100.0..16000.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(highHz=v)}}
                    }
                    NumberControl("測定レベル（共通出力）",vm.session.masterDb,"dB",-60.0..0.0,vm::stop,slider=false) {v->vm.update {it.copy(masterDb=v)}}
                    if(!tone) Choice("FIR長",s.taps,listOf(257,513,1025).map {"$it taps" to it},true) {v->vm.autoSettings {it.copy(taps=v)}}
                    TextButton(onClick={advanced=!advanced}) {Text("高度な設定")}
                    if(advanced) {
                        Choice("録音プリセット",s.preset,listOf("VOICE_RECOGNITION" to 6,"VOICE_COMMUNICATION" to 7,"UNPROCESSED" to 9),true) {v->vm.autoSettings {it.copy(preset=v)}}
                        Choice("入力PCM",s.inputRate,listOf(8000,16000,32000,44100,48000).map {"$it Hz mono" to it},true) {v->vm.autoSettings {it.copy(inputRate=v,highHz=min(it.highHz,v*0.44))}}
                        if(!tone) {
                        Choice("測定信号",s.signal,listOf("対数スイープ" to ProbeKind.SWEEP,"位相既知マルチトーン" to ProbeKind.MULTITONE),true) {v->vm.autoSettings {it.copy(signal=v)}}
                        NumberControl("各スイープ",s.seconds,"秒",1.0..5.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(seconds=v)}}
                        }
                        NumberControl("残響保持",s.tailSeconds,"秒",0.1..2.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(tailSeconds=v)}}
                        NumberControl("同期探索余裕",s.latencySeconds,"秒",0.2..3.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(latencySeconds=v)}}
                        if(!tone) {
                        NumberControl("正則化",s.regularization,"",0.0001..1.0,vm::stop,slider=false,digits=4) {v->vm.autoSettings {it.copy(regularization=v)}}
                        NumberControl("最大ブースト",s.maxBoostDb,"dB",0.0..12.0,vm::stop,slider=false) {v->vm.autoSettings {it.copy(maxBoostDb=v)}}
                        }
                    }
                } else Text("測定中は音源・処理・ルートの変更をロックしています。")
            }
            val results: @Composable ()->Unit = {
                if(diagnostic!=null && diagnostic.optString("method")!="TONE") DiagnosticSummary(diagnostic)
                Text(vm.autoProgress.detail, fontSize=14.sp)
                Text(if(vm.measurementRoute.frames>0) "MIC ${fmt(vm.measurementRoute.inputDb)} dBFS / クリップ ${vm.measurementRoute.clips}" else "MICレベル・クリップ: 未評価")
                Text(vm.measurementRoute.effects,fontSize=12.sp)
                if(vm.maintainingRoute) {
                    Text("校正条件を維持するためMIC録音・測定出力経路を維持中（音声モニターなし）",color=RightOrange)
                    OutlinedButton(onClick={vm.stop();vm.releaseHeldRoute()}) {Text("MIC・通信経路を解放")}
                }
                Text(vm.calibrationStatus(),fontSize=13.sp)
                FlowRow {
                    OutlinedButton(onClick=vm::undoAuto,enabled=!busy && (vm.session.calibration!=null || vm.session.toneProfile!=null)) {Text("元に戻す")}
                    OutlinedButton(onClick={export(vm.exportAutoResult())},enabled=!busy && (vm.session.calibration!=null || vm.autoDiagnostic.isNotEmpty())) {Text("結果保存")}
                }
                if(diagnostic!=null) {if(diagnostic.optString("method")=="TONE") ToneDiagnosticPanel(diagnostic) else DiagnosticPanel(vm,diagnostic)}
                Text("1測定点でのMIC録音上の相対低減量です。未校正の音圧dB SPLではありません。MICを固定して左右を確認してください。HFP内部のAGC・NS等は解除できるとは限りません。",fontSize=12.sp)
                Text(if(tone) "単一周波数: 前後基準と候補の窓ばらつき・ノイズ床を含む保守的低減量で、片側／両側比とも3 dB以上を2回確認。基準変動1 dB・候補窓変動1.5 dB以内。" else "FIR厳密診断: 20 dB・1 dB・5°。試験ポリシーtrial-power-v1: 20 dB・2 dB・20°、有効率80%以上。固定評価ビンで保守的低減量3 dB以上・部分帯域悪化3 dB以内を2信号各2回確認。",fontSize=12.sp)
            }
            if(maxWidth>=650.dp) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState())) {controls()}
                Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) {results()}
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {controls();results()}
        }
    }
}

@Composable private fun DiagnosticPanel(vm: AppViewModel, d: org.json.JSONObject) {
    val q=d.optJSONObject("quality")
    val settings=d.optJSONObject("settings")
    val low=settings?.optDouble("lowHz") ?: vm.session.autoFir.lowHz
    val high=settings?.optDouble("highHz") ?: vm.session.autoFir.highHz
    Text("測定時の要求: ${d.optString("requested","未評価")}",fontSize=11.sp)
    Text("測定時の実ルート: ${d.optString("actual","未評価")}",fontSize=11.sp)
    Text(d.optString("pcm","PCM未評価"),fontSize=11.sp)
    Text(d.optString("effects","入力効果は未評価"),fontSize=11.sp)
    Text("測定時の入力クリップ: ${if(d.has("clips")) d.optLong("clips").toString() else "未評価"}",fontSize=11.sp)
    val suggestion=q?.optJSONObject("suggestion")
    if(q!=null && !q.optBoolean("eligible") && suggestion==null) Text("連続した有効帯域は未検出です。MIC固定・周囲の静音・録音プリセットを確認してください。",fontSize=12.sp)
    if(suggestion!=null && !q.optBoolean("eligible")) {
        Text("連続有効帯域の候補: ${fmt(suggestion.getDouble("lowHz"),0)}–${fmt(suggestion.getDouble("highHz"),0)} Hz。自動縮小はしていません。")
        OutlinedButton(onClick=vm::applySuggestedBand,enabled=!vm.autoBusy) {Text("提案帯域に変更して再測定")}
    }
    val warnings=d.optJSONArray("warnings")
    if(warnings!=null) for(i in 0 until warnings.length()) Text(warnings.getJSONObject(i).optString("message"),fontSize=12.sp,color=RightOrange)
    val t=d.optJSONObject("timing")
    if(t!=null) {
        Text("パイロット時刻モデルの残差: 最大 ${fmt(t.optDouble("residual48k"),3)} sample / ${fmt(t.optDouble("residualMicroseconds"),2)} µs、RMS ${fmt(t.optDouble("rmsResidual48k"),3)} sample")
        Text("クロック差 ${fmt(t.optDouble("clockPpm"),2)} ppm / 相対音響位相の誤差を直接表す値ではありません",fontSize=12.sp)
        val pilots=t.optJSONArray("pilots")
        if(pilots!=null) {
            Chart(DoubleArray(pilots.length()) {pilots.getJSONObject(it).getDouble("signedResidual48k")},null,null,null,null,"送信順 / 符号付き残差","パイロット残差 sample")
            for(i in 0 until pilots.length()) {
                val p=pilots.getJSONObject(i)
                Text("#${i+1} 相関 ${fmt(p.getDouble("correlation"),3)} / 競合比 ${fmt(p.getDouble("competitorRatio"),3)} / 残差 ${fmt(p.getDouble("signedResidual48k"),3)} sample",fontSize=11.sp)
            }
        }
    } else Text("パイロット時刻モデル: 未評価")
    if(q!=null) {
        val bins=(0..TransferEstimator.SIZE/2).filter {it*SAMPLE_RATE.toDouble()/TransferEstimator.SIZE in low..high}
        fun values(a: org.json.JSONArray)=DoubleArray(bins.size) {a.optDouble(bins[it],Double.NaN)}
        for(channel in listOf("LEFT","RIGHT")) {
            val reasons=q.getJSONArray("reasons")
            Text((if(channel=="LEFT") "左" else "右")+" 不合格率: "+listOf("SNR","REPEAT_GAIN","REPEAT_PHASE").joinToString {reason ->
                val count=bins.count {k->val a=reasons.getJSONArray(k);(0 until a.length()).any {a.getString(it)=="${channel}_$reason"}}
                "$reason ${fmt(count*100.0/bins.size)}%"
            },fontSize=12.sp)
        }
        val l=q.getJSONObject("left");val r=q.getJSONObject("right")
        fun magnitude(o: org.json.JSONObject)=DoubleArray(bins.size) {val k=bins[it];levelDb(hypot(o.getJSONArray("real").getDouble(k),o.getJSONArray("imaginary").getDouble(k)))}
        Chart(magnitude(r),magnitude(l),null,null,null,"青=左 緑=右 / Hz範囲は評価帯域","伝達特性 dB")
        Chart(DoubleArray(bins.size) {val k=bins[it];wrapPhase((atan2(r.getJSONArray("imaginary").getDouble(k),r.getJSONArray("real").getDouble(k))-
            atan2(l.getJSONArray("imaginary").getDouble(k),l.getJSONArray("real").getDouble(k)))*180/PI)},null,null,-180.0,180.0,"左右共通の時刻基準 / 右−左","伝達特性の相対位相 °")
        Chart(values(r.getJSONArray("snrDb")),values(l.getJSONArray("snrDb")),null,null,null,"青=左 緑=右 / 基準20 dB","周波数別SNR dB")
        Chart(values(q.getJSONArray("rightRepeatDb")),values(q.getJSONArray("leftRepeatDb")),null,0.0,null,"青=左 緑=右 / 厳密1 dB・試験2 dB","反復振幅差 dB")
        Chart(values(q.getJSONArray("rightRepeatPhaseDeg")),values(q.getJSONArray("leftRepeatPhaseDeg")),null,0.0,180.0,"青=左 緑=右 / 厳密5°・試験20°","反復位相差 °")
        Chart(values(q.getJSONArray("weights")),null,null,0.0,1.0,"補正前に固定 / 0は評価対象外","評価重み")
    }
    for(key in listOf("designSignalTransfers","bandNoiseTransfers")) {
        val spectra=d.optJSONArray(key) ?: continue
        if(spectra.length()>=3) {
            fun power(i: Int): DoubleArray {
                val a=spectra.getJSONObject(i).getJSONArray("recordedPower")
                val bins=(0 until a.length()).filter {it*SAMPLE_RATE.toDouble()/TransferEstimator.SIZE in low..high}
                return DoubleArray(bins.size) {10*log10(a.getDouble(bins[it]).coerceAtLeast(1e-24))}
            }
            Chart(power(2),power(1),power(0),null,null,"青=両側 緑=補正後 点線=片側 / $key","MIC録音スペクトル dB")
        }
    }
    Text("理由コード: ${d.optString("reasonCode","未評価")}",fontSize=12.sp)
}

@Composable private fun DiagnosticSummary(d: org.json.JSONObject) {
    val q=d.optJSONObject("quality")
    val strict=d.optJSONObject("strictQuality") ?: q
    Text("厳密診断: ${if(strict?.optBoolean("eligible")==true) "合格" else "不足"} / 有効率 ${strict?.let {fmt(it.optDouble("coverage")*100)} ?: "未評価"}%")
    Text("試験判定: ${if(q?.optBoolean("eligible")==true) "品質条件成立" else "品質不足"} / ${d.optJSONObject("trialPolicy")?.optString("version") ?: "旧厳密条件"}")
    val settings=d.optJSONObject("settings") ?: return
    val low=settings.optDouble("lowHz");val high=settings.optDouble("highHz")
    fun status(key: String)=if(!d.has(key)) "未評価" else if(d.optBoolean(key)) "可" else "未成立"
    Text("測定時の判定: 解析 ${status("analyzable")} / 試験 ${status("trialEligible")} / 正式反映 ${status("applicable")}")
    Text("評価帯域 ${fmt(low,0)}–${fmt(high,0)} Hz / 要求帯域の有効率 ${q?.let {fmt(it.optDouble("coverage")*100)+"%"} ?: "未評価"}")
    val ver=d.optJSONArray("verification")
    if(ver==null) Text("片側比／両側比の低減量: 未評価") else for(i in 0 until ver.length()) {
        val v=ver.getJSONObject(i);val r=v.getJSONObject("assessment").optJSONObject("result")
        Text("${v.optString("signal")} ${v.optInt("repeat")}: "+(r?.let {"片側比 ${fmt(it.optDouble("singleReductionDb"))} dB / 両側比 ${fmt(it.optDouble("bothReductionDb"))} dB"} ?: "未評価"))
    }
    Text("実際の測定条件・詳細は下へスクロール",fontSize=11.sp)
}

@Composable internal fun OutputSelector(vm: AppViewModel) {
    val devices=remember(vm.routeRevision) {vm.routes.devices(false)}
    Choice("音声出力",vm.session.autoFir.output,listOf("自動（内蔵）" to null)+devices.map {it.description() to it.key()},!vm.autoBusy) {key ->vm.autoSettings {it.copy(output=key)}}
}

@Composable private fun ToneDiagnosticPanel(d: org.json.JSONObject) {
    Text("単一周波数 ${fmt(d.optDouble("frequencyHz"),1)} Hz / ${d.optString("policy")}")
    Text("判定: "+when {
        d.optBoolean("applicable") -> "検証済み（測定当時）"
        d.optString("reasonCode")=="RECORDING_LEVEL_UNSTABLE" -> "録音レベル不安定"
        d.optString("reasonCode")=="INSUFFICIENT_REDUCTION" -> "効果不足・未反映"
        d.has("candidate") -> "候補・未反映"
        else -> "未測定／探索中・未反映"
    })
    val candidate=d.optJSONObject("candidate")
    if(candidate!=null) Text("候補 右／左 ${fmt(candidate.optDouble("rightOverLeftDb"))} dB / 右−左 ${fmt(candidate.optDouble("rightMinusLeftPhaseDegrees"))}°")
    val readings=d.optJSONArray("readings")
    if(readings!=null) {
        val coarse=(0 until readings.length()).map {readings.getJSONObject(it)}.filter {it.optString("tag").startsWith("search")}.take(12)
            .sortedBy {it.getJSONObject("point").getDouble("rightMinusLeftPhaseDegrees")}
        if(coarse.isNotEmpty()) Chart(DoubleArray(coarse.size) {powerDb(coarse[it].getDouble("meanPower"))},null,null,null,null,
            "右−左位相 −180°〜150°・30°刻み / 共通出力固定","位相に対する録音電力 dBFS")
        Text("取得 ${readings.length()} 条件 / 実効待ち ${fmt(d.optDouble("effectiveWaitSeconds"))}秒 / 詳細窓・順序・フレームは結果保存",fontSize=12.sp)
    }
    val verification=d.optJSONArray("verification")
    if(verification!=null) for(i in 0 until verification.length()) {
        val v=verification.getJSONObject(i)
        Text("再検証${i+1}: 保守的低減量 片側比 ${fmt(v.optDouble("singleLowerDb"))} dB / 両側比 ${fmt(v.optDouble("bothLowerDb"))} dB")
        Text("前後変動 ${fmt(v.optDouble("driftDb"))} dB / 候補窓変動 ${fmt(v.optDouble("candidateSpreadDb"))} dB",fontSize=12.sp)
    }
    Text("理由: ${d.optString("reasonCode","未評価")} / ${d.optString("detail")}",fontSize=12.sp)
}
