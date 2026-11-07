@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.hebowiz.speakerarray

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.*

@Composable fun ExperimentalFirPage(vm: AppViewModel, export: (String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val busy=vm.autoBusy
    var permissionRevision by remember {mutableIntStateOf(0)}
    val required=listOf(Manifest.permission.RECORD_AUDIO)+if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    val permitted=remember(permissionRevision) {required.all {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {permissionRevision++}
    var archiveToken by rememberSaveable {mutableStateOf<String?>(null)}
    val archiveSave=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        val token=archiveToken
        if(token!=null) {
            if(uri==null) vm.completeExperimentalArchive(token,false)
            else vm.saveExperimentalArchive(token) {text->withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use {it.write(text)}
            }}
        }
        archiveToken=null
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) scope.launch {
        try {
            val raw=withContext(Dispatchers.IO) {context.contentResolver.openInputStream(uri)!!.bufferedReader().use {reader->
                val text=StringBuilder();val buffer=CharArray(65536)
                while(true) {val n=reader.read(buffer);if(n<0) break;require(text.length+n<=ExperimentalFir.LISTENING_MAX_CHARS) {"JSONは3200万文字までです"};text.append(buffer,0,n)}
                text.toString()}}
            vm.selectExperimentalData(raw)
        } catch(e: Exception) {vm.notify("読込失敗: ${e.message}")}
    }}
    val c=vm.session.experiments.current()
    var cleanupIds by remember(c?.id) {mutableStateOf(setOf<String>())}
    val report=remember(c?.report) {c?.let {JSONObject(it.report)}}
    var details by remember {mutableStateOf(false)}
    var comparisonIndex by remember(c?.id,c?.comparisons?.size) {mutableIntStateOf((c?.comparisons?.lastIndex ?: -1))}
    val comparison=remember(c?.id,c?.comparisons,comparisonIndex) {c?.comparisons?.getOrNull(comparisonIndex)?.let(::JSONObject)}
    Column(Modifier.fillMaxSize().padding(horizontal=8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={vm.experimentalPage=false},enabled=!busy) {Text("自動設計へ戻る")}
            Text("実験用FIR",modifier=Modifier.padding(vertical=12.dp))
            OutlinedButton(onClick={vm.cancelAuto()},enabled=busy) {Text("取消")}
            if(!permitted) OutlinedButton(onClick={permissions.launch(required.toTypedArray())},enabled=!busy) {Text("比較用MIC権限を許可")}
        }
        Text(vm.experimentalStatus,color=RightOrange,fontSize=13.sp)
        BoxWithConstraints(Modifier.weight(1f)) {
            val controls: @Composable ()->Unit = {
                Text("測定データを選択")
                Button(onClick={vm.selectExperimentalData(vm.autoDiagnostic)},enabled=!busy && vm.autoDiagnostic.isNotEmpty()) {Text("最新の診断結果を選択")}
                OutlinedButton(onClick={open.launch(arrayOf("application/json","text/*","application/octet-stream"))},enabled=!busy) {Text("保存診断／候補JSONを読込")}
                vm.experimentalData?.let {Text("データ ${it.sourceHash.take(12)} / ${it.settings.lowHz}–${it.settings.highHz} Hz / ${it.masterDb} dB",fontSize=12.sp)}
                vm.experimentalSettings?.let {s->
                    if(!busy) {
                        Choice("設計",vm.experimentalMethod,listOf("2反復の集約" to "aggregate","反復1→2" to "repeat1-to-2","反復2→1" to "repeat2-to-1"),true) {vm.experimentalMethod=it}
                        Choice("タップ数",s.taps,listOf(257,513,1025).map {it.toString() to it},true) {v->vm.editExperimentalSettings {it.copy(taps=v)}}
                        NumberControl("正則化",s.regularization,"",0.0001..1.0,vm::stop,slider=false,digits=4) {v->vm.editExperimentalSettings {it.copy(regularization=v)}}
                        NumberControl("最大ブースト",s.maxBoostDb,"dB",0.0..12.0,vm::stop,slider=false) {v->vm.editExperimentalSettings {it.copy(maxBoostDb=v)}}
                        NumberControl("設計下限",s.lowHz,"Hz",80.0..10000.0,vm::stop,slider=false) {v->vm.editExperimentalSettings {it.copy(lowHz=v)}}
                        NumberControl("設計上限",s.highHz,"Hz",100.0..16000.0,vm::stop,slider=false) {v->vm.editExperimentalSettings {it.copy(highHz=v)}}
                    }
                }
                Button(onClick=vm::generateExperimental,enabled=!busy && vm.experimentalData!=null) {Text("実験用候補を作成")}
                Text("試聴用: 帯域内の応答を等重みで設計。品質不合格ビンも含めます。",fontSize=12.sp)
                Text("生成では現在のFIRを変更しません。条件変更後は別IDの候補になります。",fontSize=12.sp)
                ListeningCandidateControls(vm)
                c?.let {candidate->
                    Text("ID ${candidate.id}\n元測定 ${fmt(report?.optDouble("measuredLowHz") ?: Double.NaN)}–${fmt(report?.optDouble("measuredHighHz") ?: Double.NaN)} Hz（旧候補の不明値は—）\n設計 ${candidate.settings.lowHz}–${candidate.settings.highHz} Hz / ${candidate.settings.taps} taps\n比較評価 ${candidate.settings.lowHz}–${candidate.settings.highHz} Hz\n係数 ${candidate.coefficientHash ?: "生成失敗"}",fontSize=12.sp)
                    val block=vm.measurementBlockReason()
                    if(block!=null) Text(block,color=RightOrange,fontSize=12.sp)
                    Text("比較は現在の共通出力 ${vm.session.masterDb} dB・選択中の端点・左右割当を使用します。先に自動設計画面で端点、再生画面で左右を確認してください。",fontSize=12.sp)
                    Button(onClick=vm::compareExperimental,enabled=!busy && candidate.playable && permitted && block==null) {Text("候補を比較測定")}
                    val plan=ExperimentalPlan.forSettings(vm.session.autoFir.copy(lowHz=candidate.settings.lowHz,highHz=candidate.settings.highHz))
                    Text("3ラウンド / 要求帯域全体のMIC相対電力 / 窓0.512秒×3 / 待ち ${fmt(plan.minimumWait)}〜8秒 / 最大240秒。再設計なし。",fontSize=12.sp)
                    FlowRow {
                        OutlinedButton(onClick={export(vm.exportExperimental())},enabled=!busy) {Text("候補・結果を保存")}
                    }
                    TextButton(onClick=vm::removeExperimentalCandidate,enabled=!busy) {Text("一覧から削除（先にJSON保存）")}
                    if(candidate.comparisons.isNotEmpty()) {
                        OutlinedButton(onClick={vm.prepareExperimentalArchive()?.let {a->archiveToken=a.token;archiveSave.launch("SpeakerArray-history-${candidate.id}.json")}},enabled=!busy && !vm.experimentalArchiveWriting) {Text("比較履歴を退避")}
                        Text("保存成功後に整理する履歴を選びます。候補と係数は保持します。",fontSize=12.sp)
                        val archive=vm.experimentalArchive
                        if(archive?.saved==true && archive.candidateId==candidate.id) {
                            for(raw in candidate.comparisons) {
                                val id=ExperimentalRuns.id(raw);val result=JSONObject(raw)
                                if(archive.results[id]==ExperimentalRuns.identity(raw)) Row {
                                    Checkbox(checked=id in cleanupIds,onCheckedChange={v->cleanupIds=if(v) cleanupIds+id else cleanupIds-id},enabled=!busy)
                                    Text("${id.take(16)} / ${result.optString("stage")} / ${runTime(result)}",fontSize=11.sp)
                                }
                            }
                            OutlinedButton(onClick={vm.pruneExperimentalHistory(cleanupIds);cleanupIds=emptySet()},enabled=!busy && cleanupIds.isNotEmpty()) {Text("選択した退避済み履歴を整理")}
                        }
                    }
                }
            }
            val results: @Composable ()->Unit = {
                Text("予測・実験比較は正式合格とは別の結果です",color=RightOrange)
                report?.let {d->
                    Text("設計 ${d.optInt("designBinCount")} / 要求 ${d.optInt("requestedBinCount")} ビン (${fmt(d.optDouble("designFraction")*100)}%) / 元の試験用有効率 ${fmt(d.optDouble("trialCoverage")*100)}%")
                    Text("ブースト ${fmt(d.optDouble("maxBoostDb"))} dB / DSPピーク上限 ${fmt(d.optDouble("dspPeakBound"),4)}")
                    if(c?.error!=null) Text("生成失敗: ${c.error}",color=RightOrange)
                    val predictions=d.optJSONArray("predictions")
                    if(predictions!=null) for(i in 0 until predictions.length()) {
                        val p=predictions.getJSONObject(i);val all=p.getJSONObject("fullBand");val used=p.getJSONObject("designBins")
                        Text("予測・反復${p.getInt("evaluationRepeat")} / 全帯域 片側比 ${fmt(all.optDouble("singleDb"))} / 両側比 ${fmt(all.optDouble("bothDb"))} dB")
                        Text("設計ビン 片側比 ${fmt(used.optDouble("singleDb"))} / 両側比 ${fmt(used.optDouble("bothDb"))} dB / 全帯域の低SNR ${all.getInt("lowSnrBins")}ビン・除外なし",fontSize=12.sp)
                        ExperimentalGraph(p,"有限長FIRの予測・反復${p.getInt("evaluationRepeat")}")
                        SubbandTable(p,true)
                    }
                    TextButton(onClick={details=!details}) {Text(if(details) "詳細を閉じる" else "品質不足理由・設計ビンの詳細")}
                    if(details) {Text(d.optJSONArray("notes")?.toString(1) ?: "",fontSize=12.sp);Text("連続区間 [下限Hz, 上限Hz, ビン数]\n${d.optJSONArray("runs")}",fontSize=12.sp)}
                }
                if(c!=null && c.comparisons.isNotEmpty()) {
                    Choice("過去の比較",comparisonIndex,c.comparisons.indices.map {"比較${it+1} / ${ExperimentalRuns.id(c.comparisons[it]).take(12)}" to it},!busy) {comparisonIndex=it}
                    comparison?.let {d->
                        Text("保存された比較結果・現在の効果は未検証",color=RightOrange)
                        Text(d.optString("status","測定不能／中止"))
                        Text("実行ID ${ExperimentalRuns.id(d.toString())}\n${runTime(d)} / ${d.optString("outcome",d.optString("stage"))}",fontSize=11.sp)
                        Text("候補 ${d.optString("candidateId")} / 共通出力 ${fmt(d.optDouble("masterDb"))} dB",fontSize=12.sp)
                        for((key,label) in listOf("single" to "片側比","both" to "両側比")) d.optJSONObject(key)?.let {r->
                            Text("$label ${if(!r.optBoolean("evaluable")) "評価不能" else if(r.optBoolean("noiseLimited")) "測定限界・下限 ${fmt(r.optDouble("lowerDb"))} dB" else "${fmt(r.optDouble("pointDb"))} dB / 保守的下限 ${fmt(r.optDouble("lowerDb"))} dB"}")}
                        Text("ノイズ床 ${fmt(powerDb(d.optDouble("noiseFloorPower")))} dB（帯域電力） / 基準変動 ${fmt(d.optDouble("baselineDriftDb"))} dB / 候補ばらつき ${fmt(d.optDouble("candidateSpreadDb"))} dB",fontSize=12.sp)
                        SubbandTable(d,false)
                        if(d.has("hz")) ExperimentalGraph(d,"MIC録音上の相対電力・SPLではありません")
                        if(details) {Text("ラウンド別\n${d.optJSONArray("rounds")?.toString(1)}",fontSize=12.sp)
                            val readings=d.optJSONArray("readings")
                            if(readings!=null) for(i in 0 until readings.length()) {val r=readings.getJSONObject(i);Text("${r.optString("tag")} / 待ち ${fmt(r.optDouble("waitedSeconds"))}秒 / 窓 ${r.optJSONArray("windows")}",fontSize=11.sp)}}
                    }
                }
            }
            if(maxWidth>=650.dp) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.43f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {controls()}
                Column(Modifier.weight(0.57f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {results()}
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {controls();results()}
        }
    }
}
@Composable internal fun ListeningCandidateControls(vm: AppViewModel) {
    val library=vm.session.experiments;val c=library.current();val busy=vm.autoBusy
    val report=remember(c?.report) {c?.let {JSONObject(it.report)}}
    Text("試聴用FIR",color=LeftBlue)
    if(library.candidates.isNotEmpty()) Choice("候補",c?.id ?: "",library.candidates.map {
        "${it.id.take(8)} / ${it.method} / ${if(it.playable) "生成済み" else "失敗"}" to it.id},!busy,change=vm::selectExperimentalCandidate)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=vm::applyExperimental,enabled=!busy && c?.playable==true) {Text("FIRとして反映")}
        OutlinedButton(onClick=vm::undoExperimental,enabled=!busy && library.undo!=null) {Text("反映前に戻す")}
    }
    Text(if(library.applied==c?.id && c?.processing==vm.session.processing && vm.session.correctionMode==CorrectionMode.FIR)
        "FIR反映済み・効果未検証（${c?.id}）" else vm.experimentalStatus,fontSize=12.sp,color=RightOrange)
    c?.let {
        Text("選択ID ${it.id}\n測定時 ${it.masterDb} dB / 現在の試聴時 ${vm.session.masterDb} dB",fontSize=12.sp)
        Text("品質注意: 元の有効率 ${fmt((report?.optDouble("trialCoverage") ?: Double.NaN)*100)}% / 反復差 ${fmt(report?.optDouble("repeatGainDb") ?: Double.NaN)} dB・${fmt(report?.optDouble("repeatPhaseDegrees") ?: Double.NaN)}°。比較未実施・効果不足でも反映できます。",fontSize=12.sp)
        val uniform=report?.optString("designWeightPolicy")==ExperimentalFir.LISTENING_VERSION
        Text("SNR不足 ${report?.optInt("lowSnrBinCount",-1)?.takeIf {n->n>=0}?.toString() ?: "詳細参照"} ビン / ${if(uniform) "帯域内を等重みで設計・品質不合格ビンも使用" else "保存時の設計重みを保持"}",fontSize=12.sp)
        val timing=report?.optJSONObject("timing")
        Text("同期信頼度 ${if(timing?.optBoolean("reliable")==true) "基準内" else "注意・詳細参照"} / 残差 ${fmt(timing?.optDouble("residual48k") ?: Double.NaN)} sample。品質情報は反映の合否には使いません。",fontSize=12.sp)
        if(it.error!=null) Text("生成できません: ${it.error}",color=RightOrange,fontSize=12.sp)
    }
    Text("反映後は停止します。「再生・調整」で音源を選んで再生してください。MICは不要です。再生出力・配置が測定時と異なる場合の効果は未確認です。",fontSize=12.sp)
}
private fun runTime(d: JSONObject): String=if(d.isNull("startedAtEpochMs")) "日時不明（旧履歴）" else
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",java.util.Locale.ROOT).format(java.util.Date(d.optLong("startedAtEpochMs")))
@Composable private fun SubbandTable(d: JSONObject,prediction: Boolean) {
    val bands=d.optJSONArray("subbands") ?: return
    Text(if(prediction) "固定サブバンドの予測" else "固定サブバンドのMIC相対電力",fontSize=12.sp)
    Row {Text("Hz",Modifier.weight(.28f),fontSize=11.sp);Text("片側 / 両側 dB",Modifier.weight(.40f),fontSize=11.sp);Text(if(prediction) "低SNR" else "ノイズ床 dB",Modifier.weight(.32f),fontSize=11.sp)}
    for(i in 0 until bands.length()) {
        val b=bands.getJSONObject(i)
        fun value(key: String): String {val r=b.getJSONObject(key);return if(!r.optBoolean("evaluable")) "評価不能" else if(r.optBoolean("noiseLimited")) "≥${fmt(r.optDouble("lowerDb"))}" else fmt(r.optDouble("pointDb"))}
        val p=b.optJSONObject("prediction")
        Row {Text("${fmt(b.getDouble("lowHz"),0)}–${fmt(b.getDouble("highHz"),0)}",Modifier.weight(.28f),fontSize=11.sp)
            Text(if(prediction) "${fmt(p!!.optDouble("singleDb"))} / ${fmt(p.optDouble("bothDb"))}" else "${value("single")} / ${value("both")}",Modifier.weight(.40f),fontSize=11.sp)
            Text(if(prediction) "${p!!.optInt("lowSnrBins")}ビン" else fmt(powerDb(b.optDouble("noiseFloorPower"))),Modifier.weight(.32f),fontSize=11.sp)}
        if(!prediction) Text("${b.optString("status")} / 下限 ${fmt(b.getJSONObject("single").optDouble("lowerDb"))} / ${fmt(b.getJSONObject("both").optDouble("lowerDb"))} dB / 基準変動 ${fmt(b.optDouble("baselineDriftDb"))} / 候補ばらつき ${fmt(b.optDouble("candidateSpreadDb"))} dB",fontSize=10.sp)
    }
}
@Composable private fun ExperimentalGraph(d: JSONObject,label: String) {
    Text(label,fontSize=12.sp)
    Text("青: 片側比 / 橙: 両側比 / 正: 低減・負: 増音 / 灰: 設計対象外 / 赤帯: 低SNR / ±30 dB表示",fontSize=11.sp)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {contentDescription=label}) {
        val hz=d.optJSONArray("hz") ?: return@Canvas;val n=hz.length();if(n<2) return@Canvas
        val low=d.optJSONArray("lowSnr");val outside=d.optJSONArray("outsideDesign")
        for(i in 0 until n) {
            val x=i*size.width/(n-1)
            if(outside?.optBoolean(i)==true) drawLine(Color.Gray.copy(alpha=.3f),Offset(x,0f),Offset(x,size.height),size.width/n+1)
            if(low?.optBoolean(i)==true) drawLine(Color.Red.copy(alpha=.2f),Offset(x,0f),Offset(x,size.height),size.width/n+1)
        }
        drawLine(Color.Gray,Offset(0f,size.height/2),Offset(size.width,size.height/2))
        for((key,color) in listOf("singleDb" to LeftBlue,"bothDb" to RightOrange)) {
            val a=d.optJSONArray(key) ?: continue;val path=Path();var active=false
            for(i in 0 until n) {val v=a.optDouble(i);if(!v.isFinite()) {active=false;continue}
                val x=i*size.width/(n-1);val y=(.5-v.coerceIn(-30.0,30.0)/60).toFloat()*size.height
                if(active) path.lineTo(x,y) else {path.moveTo(x,y);active=true}}
            drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
    }
    d.optJSONArray("hz")?.takeIf {it.length()>1}?.let {hz->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${fmt(hz.getDouble(0))} Hz",fontSize=11.sp)
            Text("中央線 0 dB",fontSize=11.sp)
            Text("${fmt(hz.getDouble(hz.length()-1))} Hz",fontSize=11.sp)
        }
    }
}
