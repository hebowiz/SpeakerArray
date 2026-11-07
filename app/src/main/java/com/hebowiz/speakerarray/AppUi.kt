@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.hebowiz.speakerarray

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

val LeftBlue = Color(0xFF64B5F6)
val RightOrange = Color(0xFFFFB66B)
private val Panel = Color(0xFF1A2835)
fun fmt(v: Double, digits: Int = 1) = if(v.isFinite()) String.format(Locale.ROOT,"%.${digits}f",v) else "—"

@Composable fun SpeakerArrayApp(vm: AppViewModel) {
    MaterialTheme(colorScheme=darkColorScheme(primary=LeftBlue, secondary=RightOrange, background=Color(0xFF0C151E), surface=Panel)) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var fileAction by remember { mutableStateOf("session") }
        var exportText by remember { mutableStateOf("") }
        var mapping by remember { mutableStateOf(false) }
        var csvText by remember { mutableStateOf<String?>(null) }
        var csvRate by remember { mutableStateOf("48000") }
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if(uri != null) {
                if(fileAction == "audio") vm.loadAudio(uri) else scope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { r ->
                                val limit=if(fileAction=="session") SessionRepository.MAX_SESSION_CHARS else 2_000_000
                                val chars = CharArray(65536);val result=StringBuilder();var count=0
                                while(true) {val n=r.read(chars);if(n<0) break;count+=n;require(count<=limit) {"ファイルが大きすぎます"};result.append(chars,0,n)}
                                result.toString()
                            }
                        }
                        if(fileAction == "session") vm.loadSession(text)
                        else if(text.trimStart().startsWith("{")) vm.importFir(text,null) else csvText = text
                    } catch(e: Exception) { vm.notify("読込失敗: ${e.message}") }
                }
            }
        }
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if(uri != null) scope.launch {
                try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(exportText) } }; vm.notify("保存しました") }
                catch(e: Exception) { vm.notify("保存失敗: ${e.message}") }
            }
        }
        fun export(fir: Boolean) {
            try { exportText = if(fir) SessionRepository.exportFir(vm.draft,vm.session.processing.length) else SessionRepository.encode(vm.session)
                save.launch(if(fir) "SpeakerArray-FIR.json" else "SpeakerArray-session.json")
            } catch(e: Exception) { vm.notify(e.message ?: "保存できません") }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text("SpeakerArray", fontSize=18.sp, modifier=Modifier.padding(end=12.dp))
                    listOf("再生・調整","FIR編集","理論プレビュー","フィルタ自動設計").forEachIndexed { i, name ->
                        TextButton(onClick={vm.tab=i}) { Text(name,color=if(vm.tab==i) LeftBlue else Color.LightGray) }
                    }
                    TextButton(onClick={export(false)},enabled=!vm.autoBusy) { Text("設定保存") }
                    TextButton(onClick={fileAction="session";open.launch(arrayOf("application/json","text/*","application/octet-stream"))},enabled=!vm.autoBusy) { Text("設定読込") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when(if(vm.autoBusy) 3 else vm.tab) {
                        0 -> Playback(vm, {mapping=true}, {fileAction="audio";open.launch(arrayOf("audio/*","application/ogg","application/octet-stream"))})
                        1 -> FirEditor(vm, {fileAction="fir";open.launch(arrayOf("application/json","text/*","application/octet-stream"))}, {export(true)})
                        2 -> Theory(vm)
                        else -> AutoFirPage(vm) {text->exportText=text;save.launch("SpeakerArray-measurement.json")}
                    }
                }
                HorizontalDivider()
                Footer(vm)
            }
        }
        if(!vm.session.orientationChosen) StopDialog("横置きの向き",vm::stop, onDismiss={}) {
            Text("使用する向きを選択してください。OSが回転を制限する場合も中央領域をスクロールできます。")
            Button(onClick={vm.orientation(false)}) { Text("通常の横置き") }
            Button(onClick={vm.orientation(true)}) { Text("逆向きの横置き") }
        }
        if(mapping) StopDialog("左右の確認",vm::stop,{mapping=false}) {
            Text("片側を鳴らして実際の位置を確認し、割り当ててください。向きや出力機器の変更後は再確認が必要です。")
            Text("現在: 物理左 → 音声${if(vm.session.swapped) "R" else "L"}、物理右 → 音声${if(vm.session.swapped) "L" else "R"}")
            FlowRow {
                Button(onClick={vm.solo(false)}) { Text("左だけ／解除") }
                Button(onClick={vm.solo(true)}) { Text("右だけ／解除") }
                Button(onClick=vm::start) { Text("再生") }
            }
            Button(onClick={vm.confirmMapping(false);mapping=false}) { Text("音声Lが物理左・確認済み") }
            Button(onClick={vm.confirmMapping(true);mapping=false}) { Text("音声Rが物理左・確認済み") }
            FlowRow {
                TextButton(onClick={vm.orientation(false)}) { Text("通常の横置き") }
                TextButton(onClick={vm.orientation(true)}) { Text("逆向きの横置き") }
            }
        }
        csvText?.let { text -> StopDialog("CSVの設計レート",vm::stop,{csvText=null}) {
            Text("処理レートは48000 Hzです。係数を設計したレートを指定してください。異なるレートは適用しません。")
            OutlinedTextField(csvRate,{csvRate=it},label={Text("サンプルレート Hz")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Button(onClick={vm.importFir(text,csvRate.toIntOrNull());csvText=null}) { Text("読み込む") }
        } }
        if(vm.message.isNotEmpty()) StopDialog("お知らせ",vm::stop,vm::dismissMessage) { Text(vm.message); Button(onClick=vm::dismissMessage) {Text("閉じる")} }
    }
}

@Composable fun StopDialog(title: String, stop: () -> Unit, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest=onDismiss,
        title={Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f));Button(onClick=stop,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB33F42))) {Text("停止")} }},
        text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)},
        confirmButton={}, dismissButton={TextButton(onClick=onDismiss) {Text("戻る")}})
}

@Composable fun NumberControl(label: String, value: Double, unit: String, range: ClosedFloatingPointRange<Double>, stop: () -> Unit,
    digits: Int = 1, slider: Boolean = true, step: Double = 0.0, change: (Double) -> Unit) {
    var edit by remember {mutableStateOf(false)}
    var text by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    Column {
        TextButton(onClick={text=fmt(value,digits);error="";edit=true},modifier=Modifier.heightIn(min=48.dp)) {
            Text("$label  ${fmt(value,digits)} $unit",fontFamily=FontFamily.Monospace)
        }
        if(slider) Slider(value.coerceIn(range.start,range.endInclusive).toFloat(),{x ->
            val v = if(step > 0) round(x/step)*step else x.toDouble(); change(v.coerceIn(range.start,range.endInclusive))
        },valueRange=range.start.toFloat()..range.endInclusive.toFloat(),modifier=Modifier.padding(horizontal=16.dp).heightIn(min=48.dp))
    }
    if(edit) StopDialog(label,stop,{edit=false}) {
        OutlinedTextField(text,{text=it},label={Text("$label ($unit)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=error.isNotEmpty())
        Text("${range.start} ～ ${range.endInclusive} $unit")
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={val v=text.toDoubleOrNull(); if(v==null || !v.isFinite() || v !in range) error="範囲内の数値を入力してください" else {change(if(step>0) round(v/step)*step else v);edit=false}}) {Text("反映")}
    }
}

@Composable fun Footer(vm: AppViewModel) {
    val status=vm.audio
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Button(onClick=vm::start,enabled=!vm.loading && !vm.autoBusy,modifier=Modifier.heightIn(min=52.dp)) {Text("再生")}
        Spacer(Modifier.width(6.dp))
        Button(onClick=vm::stop,modifier=Modifier.heightIn(min=52.dp).semantics {contentDescription="常設の停止"},colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB33F42))) {Text("停止")}
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.width(240.dp)) {
                if(vm.autoBusy) Text("測定レベル ${fmt(vm.session.masterDb)} dB（固定）")
                else NumberControl("共通出力",vm.session.masterDb,"dB",-60.0..0.0,vm::stop,slider=false,step=0.1) {v ->vm.update {it.copy(masterDb=v)}}
                Text("左 ${fmt(status.leftDb)} / 右 ${fmt(status.rightDb)} dBFS",fontFamily=FontFamily.Monospace,fontSize=12.sp)
            }
            Column(Modifier.widthIn(min=180.dp,max=340.dp)) {
                Text(if(vm.loading) "音声ファイル読込中・停止" else if(status.playing && status.generation != vm.generation) "設定切替中・非定常" else status.message,fontSize=12.sp)
                Text("${status.route} / 処理48 kHz",fontSize=11.sp)
                Text("クリップ ${status.clips}  出力不足 ${status.underruns}  音源読込不足 ${status.wavUnderruns}",fontSize=11.sp,color=if(status.clips>0) RightOrange else Color.LightGray)
            }
        }
    }
}

@Composable private fun ChannelCard(vm: AppViewModel, right: Boolean, modifier: Modifier) {
    val c=vm.session.effectiveProcessing().let {if(right) it.right else it.left}
    val color=if(right) RightOrange else LeftBlue
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel)) {
        Column(Modifier.padding(8.dp)) {
            Text(if(right) "右音源" else "左音源",color=color,fontSize=20.sp)
            Text("音声ch ${if(right xor vm.session.swapped) "R" else "L"}",fontSize=12.sp)
            NumberControl("ゲイン",c.gainDb,"dB",-60.0..12.0,vm::stop,step=0.1) {v -> vm.channel(right) {it.copy(gainDb=v)}}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(0,-3,-6).forEach {db ->
                    OutlinedButton(onClick={vm.channel(right) {it.copy(gainDb=db.toDouble())}}) {Text("$db dB")}
                }
            }
            Toggle("ミュート",c.muted) {v ->vm.channel(right) {it.copy(muted=v)}}
            OutlinedButton(onClick={vm.solo(right)}) {Text("この側だけ／解除")}
            Toggle("極性反転",c.inverted) {v ->vm.channel(right) {it.copy(inverted=v)}}
            Text("FIR: ${if(vm.session.correctionMode==CorrectionMode.SIMPLE) "保存中・単純補正では未使用" else if(c.enabled) firName(c.fir.mode) else "バイパス"}",fontSize=12.sp)
            TextButton(onClick={vm.selectSide(right);vm.tab=1}) {Text("${if(right) "右" else "左"}のFIR編集",color=color)}
        }
    }
}
@Composable fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min=48.dp).clickable {change(!value)},verticalAlignment=Alignment.CenterVertically) {Checkbox(value,change);Text(label,fontSize=14.sp)}
}
@Composable private fun Playback(vm: AppViewModel, mapping: () -> Unit, wav: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CorrectionSelector(vm)
        BoxWithConstraints(Modifier.weight(1f)) {
        val wide=maxWidth>=650.dp
        if(wide) Row(Modifier.fillMaxSize().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ChannelCard(vm,false,Modifier.weight(0.25f).fillMaxHeight().verticalScroll(rememberScrollState()))
            SourcePanel(vm,mapping,wav,Modifier.weight(0.5f).fillMaxHeight().verticalScroll(rememberScrollState()))
            ChannelCard(vm,true,Modifier.weight(0.25f).fillMaxHeight().verticalScroll(rememberScrollState()))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SourcePanel(vm,mapping,wav,Modifier.fillMaxWidth());ChannelCard(vm,false,Modifier.fillMaxWidth());ChannelCard(vm,true,Modifier.fillMaxWidth())
        }
        }
    }
}
@Composable internal fun CorrectionSelector(vm: AppViewModel) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(CorrectionMode.SIMPLE to "位相・振幅",CorrectionMode.FIR to "FIR").forEach {(mode,label)->
            FilterChip(vm.session.correctionMode==mode,{vm.correction(mode)},enabled=!vm.autoBusy,label={Text(label)})
        }
        val pending=vm.audio.playing && (vm.audio.generation!=vm.generation || vm.audio.switching)
        Text(if(vm.preparing) "準備中 / 選択 ${if(vm.session.correctionMode==CorrectionMode.FIR) "FIR" else "位相・振幅"}" else if(pending) "切替中 / 適用済み ${if(vm.appliedMode==CorrectionMode.FIR) "FIR" else "位相・振幅"}" else
            "${if(vm.audio.playing) "適用済み" else "停止中の選択"}: ${if(vm.session.correctionMode==CorrectionMode.FIR) "FIR" else "位相・振幅"}",modifier=Modifier.padding(12.dp),fontSize=12.sp)
    }
}
@Composable private fun SourcePanel(vm: AppViewModel, mapping: () -> Unit, wav: () -> Unit, modifier: Modifier) {
    val s=vm.session;val p=s.effectiveProcessing();val src=s.source
    var details by remember {mutableStateOf(false)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutputSelector(vm)
        if(s.correctionMode==CorrectionMode.SIMPLE) {
            val simple=s.simple
            NumberControl("右／左の振幅比",simple.ratio,"倍",0.000001..1000000.0,vm::stop,slider=false,digits=4,step=0.01) {v->vm.ratioDb(20*log10(v))}
            NumberControl("右／左の振幅比",simple.ratioDb,"dB",-72.0..72.0,vm::stop,slider=false,step=0.1,change=vm::ratioDb)
            Text("左右ゲインと連動。左を変えても共通音量を再正規化しません。0振幅はミュートを使用。",fontSize=11.sp)
            Text("${if(src.kind==SourceKind.SINE) "位相差" else "基準周波数 ${fmt(simple.referenceHz)} Hzでの位相差"} ${fmt(simple.phaseAt(if(src.kind==SourceKind.SINE) src.frequency else simple.referenceHz,src.kind==SourceKind.SINE))}°（右−左・正は右が進む）")
        } else {
            Text("FIR: 左 ${firName(s.processing.left.fir.mode)} / 右 ${firName(s.processing.right.fir.mode)} / ${p.length} taps")
            Text(vm.calibrationStatus(),fontSize=12.sp)
            if(s.calibration!=null) {
                TextButton(onClick=vm::showSavedFirResult) {Text("過去のFIR測定結果")}
                TextButton(onClick=vm::reviewFirConditions) {Text("FIR効果を再確認（条件設定へ）")}
            }
            if(s.calibration!=null && s.processing!=s.calibration!!.applied) TextButton(onClick=vm::restoreMeasuredFir) {Text("測定時のFIR設定を復元")}
            TextButton(onClick={vm.tab=1}) {Text("FIR編集へ")}
        }
        Row {
            FilterChip(src.kind==SourceKind.SINE,{vm.source {it.copy(kind=SourceKind.SINE)}},label={Text("正弦波")},modifier=Modifier.heightIn(min=48.dp))
            Spacer(Modifier.width(8.dp))
            FilterChip(src.kind!=SourceKind.SINE,{if(src.kind==SourceKind.SINE) vm.source {it.copy(kind=SourceKind.WHITE)}},label={Text("広帯域")},modifier=Modifier.heightIn(min=48.dp))
        }
        if(src.kind!=SourceKind.SINE) FlowRow { listOf(SourceKind.WHITE,SourceKind.PINK,SourceKind.SWEEP,SourceKind.WAV).forEach {kind ->
            FilterChip(selected=src.kind==kind,onClick={vm.source {it.copy(kind=kind)}},label={Text(sourceName(kind))},modifier=Modifier.padding(end=4.dp).heightIn(min=48.dp))
        } }
        if(src.kind==SourceKind.SINE) {
            NumberControl("発振器の位相差（右−左）",p.phaseDeg,"°",-180.0..180.0,vm::stop,digits=0,step=1.0) {v ->vm.processing {it.copy(phaseDeg=v)}}
            FlowRow {listOf(-90,0,90,180).forEach {v ->OutlinedButton(onClick={vm.processing {it.copy(phaseDeg=v.toDouble())}}) {Text("$v°")}}}
            NumberControl("周波数",src.frequency,"Hz",20.0..20000.0,vm::stop,slider=false,digits=0,step=1.0) {v ->vm.source {it.copy(frequency=v)}}
            Row(Modifier.horizontalScroll(rememberScrollState())) {listOf(250,500,1000,1143,2000,4000).forEach {hz ->TextButton(onClick={vm.source {it.copy(frequency=hz.toDouble())}}) {Text("$hz Hz")}}}
            Text("実効位相差 ${fmt(vm.totalPhase)}° / 発振器 ${fmt(p.phaseDeg,0)}°",fontSize=12.sp)
            Text("発振器の同相・逆相設定です。FIR・遅延を含む出力は上の全体位相差で確認します。",fontSize=12.sp)
        } else {
            if(s.correctionMode==CorrectionMode.SIMPLE) {
                NumberControl("基準周波数",s.simple.referenceHz,"Hz",20.0..20000.0,vm::stop,slider=false,step=1.0,change=vm::referenceHz)
                NumberControl("${fmt(s.simple.referenceHz)} Hzでの位相差（右−左）",s.simple.phaseAt(s.simple.referenceHz,false),"°",-100000.0..100000.0,vm::stop,slider=false,step=1.0,change=vm::broadbandPhase)
                Text("遅延による位相 −360 f Δt + 極性 ${fmt(s.simple.polarityDeg,0)}°。基準周波数の変更で遅延は変わりません。",fontSize=11.sp)
            }
            NumberControl("左右の時間差 Δt",p.delayUs,"µs",-1000.0..1000.0,vm::stop,digits=0,step=1.0) {v ->vm.processing {it.copy(delayUs=v)}}
            Text("− 左が遅れる   ／   ＋ 右が遅れる",fontSize=12.sp)
            Text("Δt = 右の追加遅延 − 左の追加遅延\n幾何学的な目安 ±${fmt(s.spacingMm/s.soundSpeed*1000)} µs",fontSize=12.sp)
            when(src.kind) {
                SourceKind.WHITE,SourceKind.PINK -> {
                    Toggle("共通の帯域制限",src.bandLimit) {v ->vm.source {it.copy(bandLimit=v)}}
                    if(src.bandLimit) BandInputs(vm)
                }
                SourceKind.SWEEP -> {
                    BandInputs(vm)
                    NumberControl("スイープ時間",src.sweepSeconds,"s",0.1..600.0,vm::stop,slider=false) {v ->vm.source {it.copy(sweepSeconds=v)}}
                    Toggle("対数スイープ",src.logarithmic) {v ->vm.source {it.copy(logarithmic=v)}}
                    Toggle("反復",src.loop) {v ->vm.source {it.copy(loop=v)}}
                }
                SourceKind.WAV -> {
                    Button(onClick=wav) {Text("音声ファイルを選択")}
                    Text("WAV・MP3・FLAC・M4A/AAC・Oggなど",fontSize=12.sp)
                    Text(if(src.wavUri.isEmpty()) "未選択" else Uri.parse(src.wavUri).lastPathSegment ?: "音声ファイル",maxLines=2,fontSize=12.sp)
                    FlowRow {MonoMode.entries.forEach {m ->FilterChip(src.mono==m,{vm.source {it.copy(mono=m)}},label={Text(when(m) {MonoMode.AVERAGE -> "(L+R)/2";MonoMode.LEFT -> "L採用";MonoMode.RIGHT -> "R採用"})})}}
                    Text("(L+R)/2では逆相成分が相殺される場合があります。",fontSize=12.sp)
                    Slider(vm.audio.position.toFloat(),{vm.engine.seek(it.toDouble())},modifier=Modifier.semantics {contentDescription="音声ファイルシーク"})
                    Toggle("ループ",src.loop) {v ->vm.source {it.copy(loop=v)}}
                    Toggle("ループ端5 msクロスフェード",src.loopFade) {v ->vm.source {it.copy(loopFade=v)}}
                }
                else -> Unit
            }
        }
        FlowRow {
            TextButton(onClick={vm.processing {Processing(length=it.length)};vm.selectSide(vm.sideRight)}) {Text("調整リセット")}
            TextButton(onClick=mapping) {Text(if(vm.needsMapping) "左右の確認・未確認" else "左右の確認")}
            TextButton(onClick={details=!details}) {Text("詳細調整")}
        }
        FlowRow { listOf(false,true).forEach {b ->
            TextButton(onClick={vm.storeAB(b)}) {Text("${if(b) "B" else "A"}に保存")}
            OutlinedButton(onClick={vm.recallAB(b)}) {Text("${if(b) "B" else "A"}を適用")}
        } }
        if(details) {
            NumberControl("相対遅延 Δt",p.delayUs,"µs",-10000.0..10000.0,vm::stop,slider=false,digits=0,step=1.0) {v ->vm.processing {it.copy(delayUs=v)}}
            Text("追加遅延: 左 ${fmt(max(0.0,-p.delayUs))} µs / 右 ${fmt(max(0.0,p.delayUs))} µs\n補間基準遅延: 両側16 sample。1 µs入力は補間近似です。",fontSize=12.sp)
            NumberControl("共通出力",s.masterDb,"dB",-60.0..0.0,vm::stop,step=0.1) {v ->vm.update {it.copy(masterDb=v)}}
            Text("処理負荷（直近ブロック最大）${fmt(vm.audio.cpuPercent)}%\nクリップ発生区間は飽和処理のため非線形です。",fontSize=12.sp)
        }
    }
}
@Composable private fun BandInputs(vm: AppViewModel) {
    NumberControl("下限／開始",vm.session.source.low,"Hz",1.0..23999.0,vm::stop,slider=false,digits=0) {v ->vm.source {it.copy(low=v)}}
    NumberControl("上限／終了",vm.session.source.high,"Hz",1.0..23999.0,vm::stop,slider=false,digits=0) {v ->vm.source {it.copy(high=v)}}
}
fun sourceName(k: SourceKind) = when(k) {SourceKind.SINE->"正弦波";SourceKind.WHITE->"白色ノイズ";SourceKind.PINK->"ピンクノイズ";SourceKind.SWEEP->"スイープ";SourceKind.WAV->"音声ファイル"}
fun firName(m: FirMode) = when(m) {FirMode.FLAT->"フラット";FirMode.EQ->"グラフィックEQ";FirMode.COEFFICIENTS->"係数";else->m.name}

@Composable private fun FirEditor(vm: AppViewModel, load: () -> Unit, save: () -> Unit) {
    var graph by remember {mutableIntStateOf(0)}
    var point by remember {mutableIntStateOf(4)}
    var paste by remember {mutableStateOf(false)}
    var pasted by remember {mutableStateOf("")}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=650.dp
        val controls: @Composable () -> Unit = {
            Column {
                FlowRow {
                    FilterChip(!vm.sideRight,{vm.selectSide(false);vm.preview()},label={Text("左",color=LeftBlue)})
                    FilterChip(vm.sideRight,{vm.selectSide(true);vm.preview()},label={Text("右",color=RightOrange)})
                }
                Toggle("左右同時編集",vm.linked) {vm.linked=it}
                Text("有効化だけでは設定をコピーしません。",fontSize=11.sp)
                Toggle("FIR有効",vm.channel().enabled) {v ->
                    if(vm.linked) vm.firProcessing {it.copy(left=it.left.copy(enabled=v),right=it.right.copy(enabled=v))}
                    else vm.firChannel(vm.sideRight) {it.copy(enabled=v)}
                }
                FlowRow {FirMode.entries.forEach {mode ->FilterChip(vm.draft.mode==mode,{vm.editFir {it.copy(mode=mode)}},label={Text(firName(mode))},modifier=Modifier.heightIn(min=48.dp))}}
                Text("FIR長（停止中のみ）")
                FlowRow {listOf(257,513,1025).forEach {n ->FilterChip(vm.session.processing.length==n,{vm.length(n)},label={Text("$n")},enabled=!vm.audio.playing)}}
                Text("線形位相の中央遅延 ${fmt((vm.session.processing.length-1)/2.0/SAMPLE_RATE*1000,3)} ms",fontSize=12.sp)
                when(vm.draft.mode) {
                    FirMode.LPF -> NumberControl("遮断周波数",vm.draft.high,"Hz",1.0..23999.0,vm::stop,slider=false) {v ->vm.editFir {it.copy(high=v)}}
                    FirMode.HPF -> NumberControl("遮断周波数",vm.draft.low,"Hz",1.0..23999.0,vm::stop,slider=false) {v ->vm.editFir {it.copy(low=v)}}
                    FirMode.BPF -> {
                        NumberControl("下側遮断",vm.draft.low,"Hz",1.0..23999.0,vm::stop,slider=false) {v ->vm.editFir {it.copy(low=v)}}
                        NumberControl("上側遮断",vm.draft.high,"Hz",1.0..23999.0,vm::stop,slider=false) {v ->vm.editFir {it.copy(high=v)}}
                    }
                    else -> Unit
                }
                if(vm.draft.mode in listOf(FirMode.LPF,FirMode.HPF,FirMode.BPF)) Text("遮断周波数は理想段差の境界です。Blackman窓で遷移が広がり、境界付近は約−6 dBです。",fontSize=12.sp)
                Toggle("自動反映（200 ms）",vm.autoApply,vm::automatic)
                FlowRow {Button(onClick=vm::applyFir) {Text("反映")};TextButton(onClick=vm::cancelFir) {Text("変更取消")}}
                Text(if(vm.designing) "設計中…" else if(vm.firPending) "未反映の目標" else if(vm.audio.playing && vm.audio.generation!=vm.generation) "音声へ切替中" else "反映済み",color=RightOrange)
            }
        }
        val graphs: @Composable () -> Unit = {
            Column {
                FlowRow {listOf("振幅","位相","群遅延","インパルス").forEachIndexed {i,n ->FilterChip(graph==i,{graph=i},label={Text(n)},modifier=Modifier.heightIn(min=48.dp))}}
                val curve=vm.draftCurve
                if(curve!=null) {
                    val values=when(graph) {0->curve.db;1->curve.phase;2->curve.groupMs;else->vm.draftImpulse}
                    val active=when(graph) {0->vm.activeCurve?.db;1->vm.activeCurve?.phase;2->vm.activeCurve?.groupMs;else->null}
                    val target=if(graph==0 && vm.draft.mode!=FirMode.COEFFICIENTS) DoubleArray(curve.hz.size) {FirDesigner.targetDb(vm.draft,curve.hz[it])} else null
                    Chart(values,active,target,if(graph==0) -24.0 else null,if(graph==0) 18.0 else null,
                        if(graph==3) "タップ 0 ～ ${vm.session.processing.length-1}" else "20 Hz  ·  100 Hz  ·  1 kHz  ·  10 kHz  ·  20 kHz",
                        when(graph) {0->"dB";1->"° unwrap";2->"ms";else->"係数"},
                        points=if(graph==0 && vm.draft.mode==FirMode.EQ) vm.draft.eq else null,
                        onPoint={index,gain ->point=index;vm.editFir {it.copy(eq=it.eq.toMutableList().also {a ->a[index]=gain})}})
                }
                Text("緑: 編集FIRの実現特性 / 青: 適用済みFIR / 破線: 目標",fontSize=12.sp)
                Text("深い零点では位相・群遅延を表示しません。短いFIRは目標を再現しきれない場合があります。",fontSize=12.sp)
                if(vm.draft.mode==FirMode.EQ) {
                    FlowRow {EQ_HZ.forEachIndexed {i,hz ->TextButton(onClick={point=i}) {Text("${fmt(hz,0)}",color=if(point==i) RightOrange else LeftBlue)}}}
                    NumberControl("${fmt(EQ_HZ[point],0)} Hz",vm.draft.eq[point],"dB",-12.0..12.0,vm::stop,step=0.1) {v ->vm.editFir {it.copy(eq=it.eq.toMutableList().also {a->a[point]=v})}}
                    FlowRow {listOf(-0.1,0.1).forEach {delta ->OutlinedButton(onClick={vm.editFir {it.copy(eq=it.eq.toMutableList().also {a->a[point]=(a[point]+delta).coerceIn(-12.0,12.0)})}}) {Text(if(delta<0) "−0.1 dB" else "+0.1 dB")}}}
                }
                if(vm.draft.mode==FirMode.COEFFICIENTS) {
                    Text("係数を末尾ゼロで補います。[1]の遅延は0で、中央フラットとは異なります。正規化はしません。",fontSize=12.sp)
                    Button(onClick={pasted="";paste=true}) {Text("複数係数を貼り付け")}
                    LazyColumn(Modifier.height(240.dp).fillMaxWidth()) {
                        itemsIndexed(vm.draft.coefficients) {index,value ->NumberControl("タップ $index",value,"",-1e6..1e6,vm::stop,digits=8,slider=false) {v ->vm.editFir {it.copy(coefficients=it.coefficients.toMutableList().also {a->a[index]=v})}}}
                    }
                }
                FlowRow {
                    TextButton(onClick={vm.copyBoth(false)}) {Text("左を両側へ")}
                    TextButton(onClick={vm.copyBoth(true)}) {Text("右を両側へ")}
                    TextButton(onClick=load) {Text("CSV / JSON読込")}
                    TextButton(onClick=save) {Text("FIR保存")}
                }
            }
        }
        if(wide) Row(Modifier.fillMaxSize().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(0.35f).fillMaxHeight().verticalScroll(rememberScrollState())) {controls()}
            Column(Modifier.weight(0.65f).fillMaxHeight().verticalScroll(rememberScrollState())) {graphs()}
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {controls();graphs()}
    }
    if(paste) StopDialog("係数の貼り付け",vm::stop,{paste=false}) {
        OutlinedTextField(pasted,{pasted=it},label={Text("実数値を空白・改行・カンマで区切る")},modifier=Modifier.height(150.dp))
        Button(onClick={vm.paste(pasted);paste=false}) {Text("係数を検証して編集")}
    }
}

@Composable private fun Theory(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("理論値・実測ではありません",color=RightOrange,fontSize=18.sp)
        if(vm.audio.playing && vm.audio.generation!=vm.generation) Text("設定切替中・非定常。グラフは直前の適用済み設定です。",color=RightOrange)
        Text("同一無指向性2音源・自由空間・遠方界。選択した1周波数の前方パターンです。後方・反射・筐体は評価対象外です。",fontSize=13.sp)
        FlowRow {
            if(vm.session.externalOutput) {
                val layout=vm.session.activeLayout
                Text("外部出力の配置（機器ごとに保存）")
                if(layout==null) Button(onClick={vm.externalLayout(SpeakerLayout())}) {Text("外部配置を設定（初期値150 mm）")}
                else {
                    NumberControl("外部音源間隔",layout.spacingMm,"mm",0.0..3000.0,vm::stop,slider=false) {v->vm.externalLayout(layout.copy(spacingMm=v))}
                    Toggle("外部配置の向きを反転",layout.reversed) {v->vm.externalLayout(layout.copy(reversed=v))}
                }
            } else NumberControl("音源間隔",vm.session.spacingMm,"mm",0.0..3000.0,vm::stop,slider=false) {v->vm.update {it.copy(spacingMm=v)}}
            NumberControl("音速",vm.session.soundSpeed,"m/s",250.0..400.0,vm::stop,slider=false) {v->vm.update {it.copy(soundSpeed=v)}}
            if (vm.session.source.kind == SourceKind.SINE) {
                Text("評価周波数: ${fmt(vm.session.previewFrequencyHz,0)} Hz（再生周波数に自動追従）",modifier=Modifier.padding(8.dp))
            } else {
                NumberControl("評価周波数",vm.session.evaluationHz,"Hz",20.0..20000.0,vm::stop,slider=false) {v->vm.update {it.copy(evaluationHz=v)}}
            }
        }
        if(vm.session.activeLayout==null) Text("外部機器の配置が未設定のため理論パターンは未評価です。")
        Chart(vm.pattern,null,null,-60.0,max(0.0,vm.pattern.maxOrNull() ?: 0.0),"−90° 左  ·  −45°  ·  0° 正面  ·  +45°  ·  +90° 右","相対dB")
        Text("固定参照: 0 dBゲインの2音源が同相加算した振幅2。共通出力ゲインを除外し、最大値では正規化しません。",fontSize=13.sp)
        Text("${sourceName(vm.session.source.kind)} / ${fmt(vm.session.previewFrequencyHz,0)} Hz。${if(vm.session.source.kind==SourceKind.SINE) "発振器の相対位相を含みます。" else "正弦波用の位相設定を含みません。"}",fontSize=13.sp)
    }
}

@Composable internal fun Chart(values: DoubleArray, other: DoubleArray?, target: DoubleArray?, low: Double?, high: Double?, axis: String, unit: String,
    points: List<Double>? = null, onPoint: (Int,Double) -> Unit = {_,_->}) {
    val finite=(values.asSequence()+(other?.asSequence() ?: emptySequence())).filter {it.isFinite()}.toList()
    val minY=min(low ?: (finite.minOrNull() ?: -1.0), finite.minOrNull()?.coerceAtLeast(if(low!=null) low else -1e12) ?: -1.0)
    val maxY=max(high ?: (finite.maxOrNull() ?: 1.0),finite.maxOrNull() ?: 1.0).let {if(it-minY < 1e-8) minY+1 else it}
    val callback by rememberUpdatedState(onPoint)
    val currentPoints by rememberUpdatedState(points)
    Column {
        Text("$unit  ${fmt(minY)} ～ ${fmt(maxY)}",fontSize=12.sp,fontFamily=FontFamily.Monospace)
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF0C151E)).semantics {contentDescription="$unit グラフ"}
            .pointerInput(points!=null,minY,maxY) {
                var selected=-1
                detectDragGestures(onDragStart={offset ->
                    if(currentPoints!=null) {
                        selected=EQ_HZ.indices.minByOrNull {abs((ln(EQ_HZ[it]/20)/ln(1000.0)*size.width)-offset.x)} ?: -1
                    }
                },onDrag={change,_->
                    if(selected>=0) {change.consume();callback(selected,(maxY-change.position.y/size.height*(maxY-minY)).coerceIn(-12.0,12.0))}
                },onDragEnd={selected=-1})
            }) {
            fun y(v: Double) = ((maxY-v)/(maxY-minY)*size.height).toFloat()
            repeat(5) {i ->val yy=size.height*i/4;drawLine(Color(0xFF344657),Offset(0f,yy),Offset(size.width,yy))}
            repeat(7) {i ->val xx=size.width*i/6;drawLine(Color(0xFF253542),Offset(xx,0f),Offset(xx,size.height))}
            fun line(data: DoubleArray?, color: Color, dashed: Boolean=false) {
                if(data==null || data.size<2) return
                val path=Path();var open=false
                data.forEachIndexed {i,v ->
                    if(v.isFinite()) {val x=i.toFloat()/(data.size-1)*size.width;val yy=y(v.coerceIn(minY,maxY));if(!open) {path.moveTo(x,yy);open=true} else path.lineTo(x,yy)} else open=false
                }
                drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=2.dp.toPx(),pathEffect=if(dashed) PathEffect.dashPathEffect(floatArrayOf(8f,6f)) else null))
            }
            line(target,Color.LightGray,true);line(other,LeftBlue);line(values,Color(0xFF83E4AE))
            points?.forEachIndexed {i,v ->drawCircle(RightOrange,6.dp.toPx(),Offset((ln(EQ_HZ[i]/20)/ln(1000.0)*size.width).toFloat(),y(v)))}
        }
        Text(axis,fontSize=11.sp)
    }
}
