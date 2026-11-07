package com.hebowiz.speakerarray

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.media.AudioDeviceInfo
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import org.json.JSONObject

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("session", 0)
    var session by mutableStateOf(Session()); private set
    var audio by mutableStateOf(AudioStatus()); private set
    var message by mutableStateOf(""); private set
    var needsMapping by mutableStateOf(true); private set
    var tab by mutableIntStateOf(0)
    var sideRight by mutableStateOf(false)
    var linked by mutableStateOf(false)
    var autoApply by mutableStateOf(true); private set
    var draft by mutableStateOf(FirSpec()); private set
    var draftCurve by mutableStateOf<ResponseAnalyzer.Curve?>(null); private set
    var activeCurve by mutableStateOf<ResponseAnalyzer.Curve?>(null); private set
    var draftImpulse by mutableStateOf(DoubleArray(0)); private set
    var pattern by mutableStateOf(DoubleArray(181)); private set
    var totalPhase by mutableDoubleStateOf(0.0); private set
    var firPending by mutableStateOf(false); private set
    var designing by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var generation by mutableLongStateOf(0); private set
    var autoBusy by mutableStateOf(false); private set
    var experimentalPage by mutableStateOf(false)
    var experimentalStatus by mutableStateOf("候補なし"); private set
    var experimentalData by mutableStateOf<ExperimentalInput?>(null); private set
    var experimentalSettings by mutableStateOf<AutoFirSettings?>(null); private set
    var experimentalMethod by mutableStateOf("aggregate")
    var experimentalArchive by mutableStateOf<ExperimentalArchive?>(null); private set
    var experimentalArchiveWriting by mutableStateOf(false); private set

    var autoProgress by mutableStateOf(AutoProgress()); private set
    var measurementRoute by mutableStateOf(MeasurementRouteState()); private set
    var autoResult by mutableStateOf<AutoMeasurementResult?>(null); private set
    var autoDiagnostic by mutableStateOf(""); private set
    var analysisJson by mutableStateOf(""); private set
    var routeRevision by mutableIntStateOf(0); private set
    var maintainingRoute by mutableStateOf(false); private set
    var systemVolume by mutableIntStateOf(0); private set
    private var measurementDeviceIds: Pair<Int,Int>?=null
    val routes = DeviceRoutes(app) { devicesChanged() }
    private fun devicesChanged() {
        routeRevision++; needsMapping=true
        if(maintainingRoute) releaseHeldRoute()
        if(engine.isRunning && !autoBusy) engine.stop("機器接続変更・左右と経路を再確認してください")
        measurementDeviceIds?.let {(output,input)->
            if(autoBusy && (routes.devices(false).none {it.id==output} || routes.devices(true).none {it.id==input})) cancelAuto("選択したデバイスが切断されました")
        }
    }
    private var autoJob: Job? = null
    private var heldAudio: MeasurementAudio? = null
    private var heldToneProfile: ToneProfile? = null
    private var heldFirProfile: CalibrationProfile? = null
    private var holdJob: Job? = null
    private var releaseJob: Job? = null
    private var autoEpoch = System.currentTimeMillis()
    private var autoTerminal = true
    private var autoBackup: Session? = null
    private var prepared: PreparedProcessing? = null
    private var prepareJob: Job? = null
    private var draftJob: Job? = null
    private var saveJob: Job? = null
    private val serializationMutex=Mutex()
    private var previewJob: Job? = null
    private var wavJob: Job? = null
    private var wavGeneration = 0L
    private var wavFile: File? = null
    private var wavKey = ""
    private val cacheCleanup = viewModelScope.async(Dispatchers.IO) {
        // Only this app's generated audio caches; no user-selected documents are deleted.
        app.cacheDir.listFiles()?.filter { it.isFile && (it.name.startsWith("wav-import") || it.name.startsWith("wav-48000") || it.name.startsWith("audio-import-") || it.name.startsWith("audio-decoded-")) }?.forEach { it.delete() }
    }
    var soloSide by mutableStateOf<Boolean?>(null); private set
    var appliedMode by mutableStateOf(CorrectionMode.FIR); private set
    private val generationModes = linkedMapOf<Long,CorrectionMode>()
    private var audibleProcessing = Processing()
    private val generations = linkedMapOf<Long,Processing>()
    val engine = AudioEngine(app, ::audioReport, { needsMapping = true })
    private fun audioReport(status: AudioStatus) {
        val changed = audio.generation != status.generation || audio.playing != status.playing
        audio = status
        generationModes[status.generation]?.let {appliedMode=it}
        generations[status.generation]?.let { audibleProcessing = it }
        if (changed) { generations.keys.removeAll { it < status.generation };generationModes.keys.removeAll {it<status.generation}; preview() }
    }
    init {
        autoDiagnostic=prefs.getString("autoDiagnostic","") ?: ""
        analysisJson=autoDiagnostic
        runCatching {
            if(autoDiagnostic.isNotEmpty()) {
                val diagnostic=JSONObject(autoDiagnostic)
                val stage=AutoStage.valueOf(diagnostic.getString("stage"))
                autoProgress=AutoProgress(if(stage==AutoStage.APPLIED) AutoStage.IDLE else stage,
                    detail=if(stage==AutoStage.APPLIED) "保存結果あり・測定経路は解除済み。保存FIRは再生可能ですが、現在の効果は未検証です" else diagnostic.getString("detail"))
            }
        }
        prefs.getString("last", null)?.let { raw -> runCatching { SessionRepository.decode(raw) }.onSuccess { session = it }.onFailure { message = "保存設定を復元できません: ${it.message}" } }
        experimentalStatus=session.experiments.current()?.let {if(!it.playable) "生成失敗: ${it.error}" else if(it.comparisons.isNotEmpty()) "比較結果あり（過去）・現在の効果は未検証" else "生成済み・未検証"} ?: "候補なし"
        draft = channel().fir
        scheduleProcessing(); previewDraft()
        if (session.source.wavUri.isNotBlank()) loadAudio(Uri.parse(session.source.wavUri), false)
    }
    fun notify(text: String) { message = text }
    fun dismissMessage() { message = "" }
    fun channel() = if (sideRight) session.processing.right else session.processing.left
    fun update(change: (Session) -> Session) {
        if(autoBusy) return
        try {
            val next = change(session); next.validate()
            val old = session
            if(old.swapped!=next.swapped || old.autoFir.output!=next.autoFir.output || old.autoFir.input!=next.autoFir.input) needsMapping=true
            if(engine.isRunning && (old.masterDb!=next.masterDb && maintainingRoute || old.swapped!=next.swapped || old.autoFir.output!=next.autoFir.output)) engine.stop("共通条件変更・停止")
            if(maintainingRoute && (old.autoFir!=next.autoFir || old.swapped!=next.swapped || old.masterDb!=next.masterDb || old.activeLayout!=next.activeLayout || old.calibration!=next.calibration || (old.toneProfile!=null && (old.source!=next.source || old.simple!=next.simple || old.reverseLandscape!=next.reverseLandscape || old.toneProfile!=next.toneProfile)))) releaseHeldRoute()
            if(engine.isRunning && old.masterDb!=next.masterDb && CalibrationPlaybackPolicy.peakBound(next.effectiveProcessing(),next.masterDb)>0.9) {
                engine.stop("出力ピーク上限・停止");message="出力ピーク上限を超えます。共通出力を下げてください"
            }
            session = next; engine.settings = next
            if (old.effectiveProcessing() != next.effectiveProcessing() || old.source.kind!=next.source.kind || old.correctionMode!=next.correctionMode) scheduleProcessing() else preview()
            persist()
        } catch (e: Exception) { message = e.message ?: "設定が不正です" }
    }
    fun processing(change: (Processing) -> Processing) = update { it.editActive(change) }
    fun firProcessing(change: (Processing)->Processing) = update {it.copy(processing=change(it.processing))}
    fun firChannel(right: Boolean, change: (Channel)->Channel) = firProcessing {if(right) it.copy(right=change(it.right)) else it.copy(left=change(it.left))}
    fun correction(mode: CorrectionMode) {
        if(autoBusy || mode==session.correctionMode) return
        val next=session.copy(correctionMode=mode)
        update {next}
    }
    fun ratioDb(db: Double) = processing {p->p.copy(right=p.right.copy(gainDb=p.left.gainDb+db))}
    fun referenceHz(hz: Double) = update {it.copy(simple=it.simple.copy(referenceHz=hz))}
    fun broadbandPhase(deg: Double) = update {it.copy(simple=it.simple.copy(delayUs=-(deg-it.simple.polarityDeg)/(360*it.simple.referenceHz)*1e6))}
    fun externalLayout(layout: SpeakerLayout) {update {it.copy(externalLayouts=it.externalLayouts+(it.layoutKey to layout))};needsMapping=true}
    fun restoreMeasuredFir() {val p=session.calibration ?: return;stop();update {it.copy(processing=p.applied,correctionMode=CorrectionMode.FIR)};selectSide(sideRight)}
    fun measurementBlockReason(): String? = runCatching {
        RoutePolicy.reason(routes.resolve(session.autoFir.output,false).type,routes.resolve(session.autoFir.input,true).type)
    }.getOrElse {it.message ?: "端点を選択してください"}
    fun channel(right: Boolean, change: (Channel) -> Channel) = processing { if (right) it.copy(right=change(it.right)) else it.copy(left=change(it.left)) }
    fun source(change: (SourceSettings) -> SourceSettings) {
        if(autoBusy) return
        val old = session.source
        val next = change(old)
        runCatching { next.validate() }.onFailure { notify(it.message ?: "音源設定が不正です"); return }
        if (old.mono != next.mono && next.wavUri.isNotBlank()) {
            loadAudio(Uri.parse(next.wavUri), false, next.mono)
            return
        }
        if (old.copy(frequency=next.frequency) != next) stop()
        try {
            val converted=session.changeSource(next)
            update { converted }
            if(old.kind==SourceKind.SINE && next.kind!=SourceKind.SINE) notify("位相・振幅側の位相指定を ${old.frequency} Hzで等価遅延へ変換しました。元の正弦波設定は保持しています")
        } catch(e: Exception) {stop();notify("音源変更を中止しました: ${e.message}")}
    }
    fun start() {
        if(autoBusy) return
        if(releaseJob?.isActive==true) {message="通信経路の解放完了までお待ちください";return}
        try {
            if(maintainingRoute) heldAudio?.check()
            engine.requestedOutput=routes.resolve(session.autoFir.output,false)
        } catch(e: Exception) {message=e.message ?: "出力先を選択してください";return}
        if (loading || prepared?.generation != generation) { message = "設定の準備が完了するまでお待ちください"; return }
        if (session.source.kind == SourceKind.WAV && wavFile == null) { message = "音声ファイルを選択してください"; return }
        engine.settings = session
        try {
            if(maintainingRoute) engine.startRetained(prepared!!,wavFile,requireNotNull(heldAudio))
            else engine.start(prepared!!, wavFile)
        } catch(e: Exception) {message=e.message ?: "再生開始失敗"}
    }
    fun stop() { engine.stop(); if(autoBusy) cancelAuto("停止操作") }
    fun background() { cancelAuto("バックグラウンド移行"); releaseHeldRoute(); engine.stop("バックグラウンド移行・停止"); persist() }
    fun solo(right: Boolean) {
        if(autoBusy) return
        soloSide=if(soloSide==right) null else right
        engine.soloSide=soloSide
        scheduleProcessing()
    }
    fun confirmMapping(swapped: Boolean) { stop(); update { it.copy(swapped=swapped) }; needsMapping = false }
    fun orientation(reverse: Boolean) { stop(); needsMapping = true; update { it.copy(reverseLandscape=reverse, orientationChosen=true) } }
    fun selectSide(right: Boolean) { sideRight = right; draftJob?.cancel(); draft = channel().fir; firPending = false; previewDraft(); preview() }
    fun automatic(enabled: Boolean) { autoApply = enabled; previewDraft() }
    fun editFir(change: (FirSpec) -> FirSpec) {
        if(autoBusy) return
        draft = change(draft); firPending = true; previewDraft()
    }
    private fun previewDraft() {
        draftJob?.cancel()
        val snapshot = draft; val n = session.processing.length
        val right = sideRight; val both = linked; val shouldApply = autoApply && firPending
        designing = true
        draftJob = viewModelScope.launch {
            delay(200)
            try {
                val (h,c) = withContext(Dispatchers.Default) { val h = FirDesigner.design(snapshot,n); h to ResponseAnalyzer.curve(h) }
                ensureActive(); draftImpulse = h; draftCurve = c; designing = false
                if (shouldApply) commitFir(snapshot, right, both)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { designing = false; message = e.message ?: "FIR設計エラー" }
        }
    }
    fun applyFir() {
        try { FirDesigner.validate(draft,session.processing.length); commitFir(draft,sideRight,linked) }
        catch (e: Exception) { message = e.message ?: "FIRエラー" }
    }
    private fun commitFir(spec: FirSpec, right: Boolean, both: Boolean) {
        firProcessing { p -> p.copy(left=if (!right || both) p.left.copy(fir=spec) else p.left, right=if (right || both) p.right.copy(fir=spec) else p.right) }
        firPending = false
    }
    fun cancelFir() { selectSide(sideRight) }
    fun copyBoth(fromRight: Boolean) {
        val original = if (fromRight) session.processing.right else session.processing.left
        firProcessing { it.copy(left=it.left.copy(fir=original.fir,enabled=original.enabled),right=it.right.copy(fir=original.fir,enabled=original.enabled)) }; selectSide(sideRight)
    }
    fun length(n: Int) {
        if (audio.playing) { message = "FIR長の変更は停止後に行ってください"; return }
        try {
            val p = session.processing.copy(length=n); p.validate()
            update { it.copy(processing=p,a=null,b=null) }; selectSide(sideRight)
        } catch (e: Exception) { message = "FIR長を変更できません: ${e.message}" }
    }
    fun storeAB(b: Boolean) { update { if (b) it.copy(b=it.processing,correctionB=it.correctionSnapshot()) else it.copy(a=it.processing,correctionA=it.correctionSnapshot()) }; message = "${if(b) "B" else "A"}に保存しました" }
    fun recallAB(b: Boolean) {
        val saved=if(b) session.correctionB else session.correctionA
        if(saved!=null) {if(saved.fir.length!=session.processing.length && engine.isRunning) {notify("A/BのFIR長が異なります。停止してから呼び出してください");return};if(saved.source!=session.source) stop();update {it.restore(saved)};selectSide(sideRight);return}
        val p = if (b) session.b else session.a
        if (p == null) { message = "比較設定が未保存です"; return }
        if(p.length!=session.processing.length && engine.isRunning) {notify("A/BのFIR長が異なります。停止してから呼び出してください");return}
        update {it.copy(processing=p,correctionMode=CorrectionMode.FIR)};selectSide(sideRight)
    }
    private fun scheduleProcessing() {
        val snapshot = session; val id = ++generation
        preparing=true
        val active=snapshot.effectiveProcessing().withSolo(soloSide)
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            delay(20)
            try {
                val value = withContext(Dispatchers.Default) { PreparedProcessing.create(active, id, snapshot.source.kind==SourceKind.SINE) }
                ensureActive(); prepared = value; generations[id] = active;generationModes[id]=snapshot.correctionMode
                if (!audio.playing) { audibleProcessing = active;appliedMode=snapshot.correctionMode; generations.keys.removeAll { it < id } }
                engine.submit(value);preparing=false; preview()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { preparing=false;engine.stop("処理設定エラー・停止");message = "処理設定エラー: ${e.message}" }
        }
        engine.settings = session
        preview()
    }
    fun preview() {
        previewJob?.cancel()
        val s = session.copy(processing=if(audio.playing) audibleProcessing else session.effectiveProcessing(),correctionMode=CorrectionMode.FIR)
        val right = sideRight
        previewJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val ch = if (right) s.processing.right else s.processing.left
                val h = if (ch.enabled) FirDesigner.design(ch.fir,s.processing.length) else FirDesigner.flat(s.processing.length)
                val l = ResponseAnalyzer.channel(s.processing,false,s.source.frequency,true)
                val r = ResponseAnalyzer.channel(s.processing,true,s.source.frequency,true)
                Triple(ResponseAnalyzer.pattern(s), ResponseAnalyzer.curve(h), if(l.magnitude < 1e-4 || r.magnitude < 1e-4) Double.NaN else wrapPhase((r.phase-l.phase)*180/Math.PI))
            }
            ensureActive(); pattern = result.first; activeCurve = result.second; totalPhase = result.third
        }
    }
    fun persist() {
        saveJob?.cancel(); val snapshot = session
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            try {
                serializationMutex.withLock {
                    ensureActive()
                    val encoded=SessionRepository.encode(snapshot)
                    ensureActive()
                    // Finish the previous disk write before allowing another large serialization.
                    check(prefs.edit().putString("last",encoded).commit()) {"設定の保存に失敗しました"}
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {withContext(Dispatchers.Main) {notify("設定の保存に失敗しました: ${e.message}")}}
        }
    }
    fun loadSession(text: String) {
        if(autoBusy) return
        try {
            val s = SessionRepository.decode(text)
            wavJob?.cancel(); wavGeneration++; loading = false
            stop();releaseHeldRoute();soloSide=null;engine.soloSide=null; needsMapping = true; update { s }; selectSide(false)
            val previous=wavFile;wavFile = null; wavKey = ""
            viewModelScope.launch(Dispatchers.IO) {previous?.delete()}
            if (s.source.wavUri.isNotBlank()) loadAudio(Uri.parse(s.source.wavUri), false)
        } catch (e: Exception) { message = "読込を中止しました。設定は保持されています: ${e.message}" }
    }
    fun importFir(text: String, rate: Int?) {
        try { val f = SessionRepository.importFir(text,session.processing.length,rate); editFir { f } }
        catch (e: Exception) { message = "FIR読込を中止しました: ${e.message}" }
    }
    fun paste(text: String) {
        try { val f = SessionRepository.pasteCoefficients(text,session.processing.length); editFir { f } }
        catch (e: Exception) { message = "係数が不正です: ${e.message}" }
    }
    fun loadAudio(uri: Uri, persistPermission: Boolean = true, mono: MonoMode = session.source.mono) {
        if(autoBusy) return
        stop(); val previousJob=wavJob;previousJob?.cancel(); loading = true
        val request = ++wavGeneration
        val key = "$uri/$mono"
        wavJob = viewModelScope.launch {
            var pendingFile: File?=null
            try {
                previousJob?.join()
                val app = getApplication<Application>()
                cacheCleanup.await()
                if (persistPermission) app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (wavKey != key || wavFile?.exists() != true) {
                    val file = withContext(Dispatchers.IO) {
                        val input = File.createTempFile("audio-import-", ".bin",app.cacheDir)
                        val output = File.createTempFile("wav-48000", ".pcm",app.cacheDir)
                        pendingFile=output
                        try {
                            app.contentResolver.openInputStream(uri)!!.use { src -> input.outputStream().use { dest ->
                                val bytes = ByteArray(65536); var total = 0L
                                while (true) { ensureActive(); val n = src.read(bytes); if(n < 0) break; total += n; require(total <= AudioFileDecoder.MAX_INPUT_BYTES) { "音声ファイルの読込上限は1 GiBです" }; dest.write(bytes,0,n) }
                            } }
                            val context=coroutineContext
                            AudioFileDecoder.convert(input,output,mono) {context.ensureActive()}; output
                        } catch(e: Exception) { output.delete(); throw e } finally { input.delete() }
                    }
                    ensureActive()
                    val previous = wavFile
                    wavFile = file; wavKey = key;pendingFile=null
                    viewModelScope.launch(Dispatchers.IO) { previous?.delete() }
                }
                update { it.copy(source=it.source.copy(wavUri=uri.toString(),mono=mono)) }
                message = "音声ファイルを48 kHzで準備しました。再生は停止中です"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message = "音声ファイルを読み込めません。未対応形式・破損・参照切れを確認してください: ${e.message}" }
            finally {
                withContext(NonCancellable+Dispatchers.IO) {pendingFile?.delete()}
                if (request == wavGeneration) loading = false
            }
        }
    }
    fun autoSettings(change: (AutoFirSettings)->AutoFirSettings) {
        if(autoBusy) return
        stop();releaseHeldRoute()
        val before=session.autoFir
        update {it.copy(autoFir=change(it.autoFir))}
        if(before.output!=session.autoFir.output || before.input!=session.autoFir.input) needsMapping=true
    }
    fun cancelAuto(reason: String="取消") {
        if(autoBusy) {autoProgress=autoProgress.copy(detail=reason);autoJob?.cancel(CancellationException(reason))}
    }
    fun releaseHeldRoute() {
        if(maintainingRoute) engine.stop("測定経路解放・停止")
        if(maintainingRoute && autoProgress.stage==AutoStage.APPLIED)
            autoProgress=AutoProgress(AutoStage.IDLE,detail="保存FIRは通常再生できます。測定経路の解除により、現在の効果は未検証です")
        holdJob?.cancel();holdJob=null
        val held=heldAudio;heldAudio=null;heldToneProfile=null;heldFirProfile=null;maintainingRoute=false
        if(held!=null) releaseJob=viewModelScope.launch(NonCancellable+Dispatchers.IO) {held.close()}
    }
    fun undoAuto() {
        if(autoBusy) return
        val backup=autoBackup ?: session.toneProfile?.let {runCatching {SessionRepository.decode(it.backup)}.getOrNull()} ?: session.calibration?.let {runCatching {SessionRepository.decode(it.backup)}.getOrNull()} ?: return
        stop();releaseHeldRoute();soloSide=null;engine.soloSide=null;update {backup};selectSide(sideRight);autoBackup=null
        autoProgress=AutoProgress(AutoStage.IDLE,detail="元のFIR・FIR長・A/B・音源・音量・ルートを復元しました")
    }
    fun applySuggestedBand() {
        if(autoBusy) return
        val suggestion=runCatching {JSONObject(analysisJson).optJSONObject("quality")?.optJSONObject("suggestion")}.getOrNull() ?: return
        autoSettings {it.copy(lowHz=suggestion.getDouble("lowHz"),highHz=suggestion.getDouble("highHz"))}
        startAuto()
    }
    fun startAuto() = startAutoRun(false)
    fun startListening() = startAutoRun(true)
    private fun startAutoRun(listening: Boolean) {
        experimentalPage=false
        if(!listening && session.adjustmentMethod==AdjustmentMethod.TONE) {startTone();return}
        if(autoBusy || loading) return
        if(listening && session.experiments.candidates.size>=12) {notify("候補は最大12件です。保存後に一覧を整理してください");return}
        if(needsMapping) {message="測定前に「再生・調整」の「左右の確認」を行ってください";return}
        val backup=session
        val output: AudioDeviceInfo;val input: AudioDeviceInfo
        try {backup.autoFir.validate();output=routes.resolve(backup.autoFir.output,false);input=routes.resolve(backup.autoFir.input,true);RoutePolicy.requireAllowed(output.type,input.type)}
        catch(e: Exception) {message=e.message ?: "デバイス未確認";return}
        releaseHeldRoute();engine.stop();autoBusy=true;autoTerminal=false;autoResult=null;autoDiagnostic="";analysisJson=""
        measurementDeviceIds=output.id to input.id
        val id=++autoEpoch
        val transaction=CalibrationTransaction(backup,id)
        val resolved=backup.autoFir.copy(output=output.key(),input=input.key())
        autoJob=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var measurement: AutoFirMeasurement?=null
            var retained=false
            try {
                releaseJob?.join()
                withTimeout(5000) {while(engine.isRunning) delay(20)}
                prepareJob?.cancelAndJoin();draftJob?.cancelAndJoin()
                measurement=AutoFirMeasurement(getApplication(),output,input,resolved,backup,
                    {p->viewModelScope.launch {if(autoEpoch==id && autoBusy && !autoTerminal) autoProgress=p}},
                    {r->viewModelScope.launch {if(autoEpoch==id) measurementRoute=r}},
                    {json->viewModelScope.launch {if(autoEpoch==id) analysisJson=json}})
                val runner=measurement
                val result=withContext(Dispatchers.Default) {runner.run(listening)}
                if(listening) {
                    val data=requireNotNull(runner.listeningInput) {"左右の伝達特性を取得できませんでした"}
                    // Close capture and communication before design; ordinary playback never needs this MIC.
                    // A route-removal notification from this deliberate release must not cancel offline design.
                    measurementDeviceIds=null
                    withContext(Dispatchers.IO) {runner.audio.close()}
                    autoProgress=AutoProgress(AutoStage.DESIGNING,.65f,"試聴用FIRを設計中・品質判定は注意表示のみ")
                    val job=coroutineContext
                    val candidate=withContext(Dispatchers.Default) {ExperimentalFir.generate(data,listening=true) {job.ensureActive()}}
                    ensureActive();check(autoEpoch==id)
                    addListeningCandidate(candidate)
                    experimentalData=data;experimentalSettings=data.settings;experimentalPage=false
                    autoProgress=AutoProgress(if(candidate.playable) AutoStage.CREATED else AutoStage.REJECTED,1f,experimentalStatus)
                    return@launch
                }
                if(result==null) {
                    transaction.rollback()
                    autoProgress=AutoProgress(AutoStage.REJECTED,detail=runner.diagnostics.optString("detail","品質不足"))
                    return@launch
                }
                ensureActive();check(autoEpoch==id)
                val resultJson=withContext(Dispatchers.Default) {MeasurementResultStore.encode(result,id)}
                val profile=CalibrationProfile(id,resolved,backup.masterDb,backup.swapped,result.volume,result.mode,
                    input.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO,result.solution.processing,
                    SessionRepository.encode(backup.copy(calibration=null)),resultJson)
                val next=transaction.accept(profile,result.verification,result.evidence)
                next.validate()
                val ready=withContext(Dispatchers.Default) {PreparedProcessing.create(next.processing,generation+1,next.source.kind==SourceKind.SINE)}
                ensureActive();runner.audio.check()
                runner.audio.retain()
                // Publish one accepted generation atomically. Trial processing never mutates session.
                generation=ready.generation;prepared=ready;preparing=false;audibleProcessing=next.processing
                session=next;soloSide=null;engine.soloSide=null;appliedMode=CorrectionMode.FIR;engine.settings=next;autoBackup=backup;autoResult=result;systemVolume=result.volume
                draft=channel().fir;firPending=false;preview();previewDraft();persist()
                autoProgress=AutoProgress(AutoStage.APPLIED,1f,"${resolved.lowHz}–${resolved.highHz} Hz・${if(profile.hfp) "HFP録音上" else "MIC録音上"}で2信号の低減確認・停止中${if(result.verification.any {it.noiseLimited}) "（ノイズ床による測定限界あり）" else ""}")
                holdMeasurementRoute(runner.audio, fir=profile);retained=true
            } catch(e: CancellationException) {transaction.rollback();autoProgress=AutoProgress(AutoStage.CANCELLED,detail=e.message ?: "取消")}
            catch(e: Exception) {transaction.rollback();autoProgress=AutoProgress(AutoStage.REJECTED,detail=e.message ?: "測定失敗")}
            finally {
                autoTerminal=true
                withContext(NonCancellable) {
                try {
                if(autoEpoch==id) {
                    autoDiagnostic=withContext(Dispatchers.IO) {serializationMutex.withLock {
                        val encoded=(measurement?.finalDiagnostics() ?: JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",2))
                        .put("generation",id).put("stage",autoProgress.stage.name).put("detail",autoProgress.detail)
                        .put("requested",measurementRoute.requested).put("actual",measurementRoute.actual)
                        .put("pcm",measurementRoute.pcm).put("effects",measurementRoute.effects)
                        .put("recordedFrames",measurementRoute.frames).put("clips",measurementRoute.clips)
                        .put("settings",CalibrationRepository.settings(resolved)).put("masterDb",backup.masterDb)
                        .apply {if(!has("reasonCode")) put("reasonCode",if(autoProgress.stage==AutoStage.CANCELLED) "CANCELLED" else MeasurementResultStore.failureCode(autoProgress.detail))}.toString()
                        check(prefs.edit().putString("autoDiagnostic",encoded).commit()) {"診断の保存に失敗しました"}
                        encoded
                    }}
                    analysisJson=autoDiagnostic
                }
                } catch(e: Exception) {notify("診断の保存に失敗しました: ${e.message}")
                } finally {
                withContext(Dispatchers.IO) {if(!retained) measurement?.audio?.close()}
                if(autoEpoch==id) {autoBusy=false;autoJob=null;measurementDeviceIds=null;if(autoProgress.stage!=AutoStage.APPLIED) {scheduleProcessing();previewDraft()}}
                }
                }
            }
        }
    }
    fun adjustment(method: AdjustmentMethod) = update {it.copy(adjustmentMethod=method,autoToneHz=if(method==AdjustmentMethod.TONE) it.source.frequency else it.autoToneHz)}
    private fun startTone() {
        if(autoBusy || loading) return
        if(needsMapping) {message="測定前に左右の確認を行ってください";return}
        val backup=session
        val output: AudioDeviceInfo;val input: AudioDeviceInfo
        try {
            TonePlan(backup.autoToneHz,backup.autoFir.referenceRight).validate(backup.autoFir.inputRate)
            output=routes.resolve(backup.autoFir.output,false);input=routes.resolve(backup.autoFir.input,true)
            RoutePolicy.requireAllowed(output.type,input.type)
            require(TonePoint().safe(backup.masterDb)) {"出力ピーク上限。共通出力を手動で下げてください"}
        } catch(e: Exception) {message=e.message ?: "開始条件不足";return}
        releaseHeldRoute();engine.stop();autoBusy=true;autoTerminal=false;autoResult=null;autoDiagnostic="";analysisJson=""
        measurementDeviceIds=output.id to input.id
        val id=++autoEpoch
        val resolved=backup.autoFir.copy(output=output.key(),input=input.key())
        autoJob=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var runner: ToneMeasurement?=null;var retained=false
            try {
                releaseJob?.join();withTimeout(5000) {while(engine.isRunning) delay(20)}
                prepareJob?.cancelAndJoin();draftJob?.cancelAndJoin()
                val measurement=ToneMeasurement(getApplication(),output,input,resolved,backup,
                    {p->viewModelScope.launch {if(autoEpoch==id && autoBusy && !autoTerminal) autoProgress=p}},
                    {r->viewModelScope.launch {if(autoEpoch==id) measurementRoute=r}},
                    {json->viewModelScope.launch {if(autoEpoch==id && !autoTerminal) analysisJson=json}})
                runner=measurement
                val result=withContext(Dispatchers.Default) {measurement.run()}
                ensureActive();check(autoEpoch==id)
                if(!result.verified) {
                    autoProgress=AutoProgress(AutoStage.REJECTED,detail=result.reason);return@launch
                }
                val profile=ToneProfile(requireNotNull(result.point).simple(measurement.plan),backup.autoToneHz,backup.masterDb,backup.swapped,
                    resolved,measurement.audio.volumeIndex,measurement.audio.mode,backup.copy(autoFir=resolved).activeLayout,backup.reverseLandscape,
                    SessionRepository.encode(backup.copy(toneProfile=null)),measurement.diagnostics.toString())
                val next=ToneTransaction.accept(backup,result,profile)
                val ready=withContext(Dispatchers.Default) {PreparedProcessing.create(next.effectiveProcessing(),generation+1,true)}
                ensureActive();measurement.audio.check();measurement.audio.retain()
                generation=ready.generation;prepared=ready;preparing=false;audibleProcessing=next.effectiveProcessing()
                session=next;soloSide=null;engine.soloSide=null;appliedMode=CorrectionMode.SIMPLE;engine.settings=next;autoBackup=backup
                draft=channel().fir;firPending=false;preview();previewDraft();persist()
                autoProgress=AutoProgress(AutoStage.APPLIED,1f,"${next.autoToneHz} Hz・指定条件でMIC録音上の低減を確認・停止中"+(if(result.edge) " / ゲイン探索範囲端" else ""))
                holdMeasurementRoute(measurement.audio, tone=profile);retained=true
            } catch(e: CancellationException) {autoProgress=AutoProgress(AutoStage.CANCELLED,detail=e.message ?: "取消")}
            catch(e: Exception) {autoProgress=AutoProgress(AutoStage.REJECTED,detail=e.message ?: "測定失敗")}
            finally {
                autoTerminal=true
                withContext(NonCancellable) {
                    try {
                        val d=runner?.diagnostics ?: JSONObject().put("type","speakerarray-diagnostic").put("method","TONE")
                        d.put("stage",autoProgress.stage.name).put("detail",autoProgress.detail).put("applicable",retained)
                            .put("settings",CalibrationRepository.settings(resolved)).put("frequencyHz",backup.autoToneHz)
                        if(!retained) d.put("reasonCode",when {
                            autoProgress.stage==AutoStage.CANCELLED -> "CANCELLED"
                            "録音レベル不安定" in autoProgress.detail -> "RECORDING_LEVEL_UNSTABLE"
                            d.has("reasonCode") -> d.getString("reasonCode")
                            else -> MeasurementResultStore.failureCode(autoProgress.detail)
                        })
                        autoDiagnostic=withContext(Dispatchers.IO) {serializationMutex.withLock {
                            val encoded=d.toString()
                            check(prefs.edit().putString("autoDiagnostic",encoded).commit()) {"診断の保存に失敗しました"}
                            encoded
                        }}
                        analysisJson=autoDiagnostic
                    } catch(e: Exception) {notify("診断の保存に失敗しました: ${e.message}")
                    } finally {
                        withContext(Dispatchers.IO) {if(!retained) runner?.audio?.close()}
                        if(autoEpoch==id) {autoBusy=false;autoJob=null;measurementDeviceIds=null;if(!retained) {scheduleProcessing();previewDraft()}}
                    }
                }
            }
        }
    }
    fun selectExperimentalData(raw: String) {
        if(autoBusy) return
        experimentalPage=true
        try {
            if(JSONObject(raw.removePrefix("\uFEFF")).optString("type")=="speakerarray-experimental-fir") {
                val c=ExperimentalRepository.import(raw)
                require(session.experiments.candidates.none {it.id==c.id}) {"同じ候補IDは読込済みです"}
                val next=session.copy(experiments=session.experiments.copy(candidates=session.experiments.candidates+c,selected=c.id));next.validate()
                update {next}
                experimentalStatus=if(c.playable) "生成済み・未検証（保存候補を読込）" else "生成失敗: ${c.error}"
            } else {
                val input=ExperimentalFir.readDiagnostic(raw,listening=true)
                experimentalData=input;experimentalSettings=input.settings;experimentalStatus="測定データ選択済み・${input.sourceHash.take(12)}"
            }
        } catch(e: Exception) {experimentalData=null;experimentalSettings=null;experimentalStatus="生成禁止・測定データ不足: ${e.message}"}
    }
    fun editExperimentalSettings(change: (AutoFirSettings)->AutoFirSettings) {
        if(autoBusy) return
        experimentalSettings?.let {s->runCatching {change(s).also {it.validate()}}.onSuccess {experimentalSettings=it}.onFailure {notify(it.message ?: "設計条件不正")}}
    }
    fun selectExperimentalCandidate(id: String) {if(!autoBusy) {
        update {it.copy(experiments=it.experiments.copy(selected=id))}
        val c=session.experiments.current();experimentalStatus=if(c?.playable==true) "生成済み・未検証 / 保存比較 ${c.comparisons.size}件" else "生成失敗: ${c?.error}"
    }}
    fun removeExperimentalCandidate() {
        if(autoBusy) return
        val id=session.experiments.selected ?: return
        update {s->val v=s.experiments;val remaining=v.candidates.filterNot {it.id==id}
            s.copy(experiments=v.copy(candidates=remaining,selected=remaining.lastOrNull()?.id,applied=v.applied?.takeUnless {it==id}))}
        experimentalStatus="候補一覧から削除しました。現在のFIR係数は保持しています"
    }
    fun generateExperimental() {
        if(autoBusy) return
        val input=experimentalData ?: return;val config=experimentalSettings ?: return;val method=experimentalMethod
        if(session.experiments.candidates.size>=12) {notify("候補は最大12件です。JSONへ保存後に一覧を整理してください");return}
        engine.stop();autoBusy=true;experimentalPage=true;experimentalStatus="候補生成中";autoTerminal=false
        autoJob=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val job=coroutineContext
                val c=withContext(Dispatchers.Default) {ExperimentalFir.generate(input,method,config,listening=true) {job.ensureActive()}}
                ensureActive()
                addListeningCandidate(c)
            } catch(e: CancellationException) {experimentalStatus="生成取消・未反映"}
            catch(e: Exception) {experimentalStatus="生成失敗: ${e.message}"}
            finally {autoBusy=false;autoJob=null;autoTerminal=true}
        }
    }
    private fun addListeningCandidate(c: ExperimentalCandidate) {
        val next=session.copy(experiments=session.experiments.copy(candidates=session.experiments.candidates+c,selected=c.id))
        next.validate();session=next;persist()
        experimentalStatus=if(c.playable) "試聴用FIRを作成しました・品質注意・効果未検証" else "生成失敗: ${c.error}"
    }
    fun applyExperimental() {
        if(autoBusy) return
        val c=session.experiments.current() ?: return
        try {
            val p=requireNotNull(c.processing) {c.error ?: "候補なし"}
            ExperimentalFir.validateProcessing(p,c.settings,session.masterDb)
            val backup=SessionRepository.encode(session.copy(experiments=ExperimentalLibrary()))
            val next=session.copy(processing=p,correctionMode=CorrectionMode.FIR,
                experiments=session.experiments.copy(applied=c.id,undo=backup))
            next.validate()
            stop();releaseHeldRoute();soloSide=null;engine.soloSide=null
            update {next}
            selectSide(sideRight);experimentalStatus="FIR反映済み・効果未検証（${c.id}）"
        } catch(e: Exception) {notify("反映できません: ${e.message}")}
    }
    fun undoExperimental() {
        if(autoBusy) return
        val raw=session.experiments.undo ?: return
        try {val old=SessionRepository.decode(raw);val library=session.experiments.copy(applied=null,undo=null)
            stop();releaseHeldRoute();soloSide=null;engine.soloSide=null;update {old.copy(experiments=library)};selectSide(sideRight)
            experimentalStatus="反映前の設定へ復元・停止中"
        } catch(e: Exception) {notify("復元失敗: ${e.message}")}
    }
    fun exportExperimental(): String = session.experiments.current()?.let(ExperimentalRepository::export) ?: ""
    fun prepareExperimentalArchive(): ExperimentalArchive? {
        if(autoBusy || experimentalArchiveWriting) return null
        return session.experiments.current()?.let {ExperimentalArchive.prepare(it).also {a->experimentalArchive=a}}
    }
    fun completeExperimentalArchive(token: String,success: Boolean) {
        experimentalArchive?.takeIf {it.token==token}?.let {experimentalArchive=it.completed(success)}
        notify(if(success) "退避を保存しました。整理する履歴を選択してください" else "退避は未完了です。履歴は保持しています")
    }
    fun saveExperimentalArchive(token: String,writer: suspend (String)->Unit)=viewModelScope.launch {
        val archive=experimentalArchive?.takeIf {it.token==token} ?: return@launch
        if(experimentalArchiveWriting) return@launch
        experimentalArchiveWriting=true
        try {writer(archive.payload);completeExperimentalArchive(token,true)}
        catch(e: Exception) {completeExperimentalArchive(token,false);notify("退避保存失敗: ${e.message}")}
        finally {experimentalArchiveWriting=false}
    }
    fun pruneExperimentalHistory(ids: Set<String>) {
        if(autoBusy || experimentalArchiveWriting) return
        try {
            val c=requireNotNull(session.experiments.current());val archive=requireNotNull(experimentalArchive)
            val next=archive.remove(c,ids)
            update {s->s.copy(experiments=s.experiments.copy(candidates=s.experiments.candidates.map {if(it.id==c.id) next else it}))}
            experimentalStatus="選択した比較履歴を整理しました・同じ候補で再測定できます"
        } catch(e: Exception) {notify("履歴整理を中止: ${e.message}")}
    }
    fun compareExperimental() {
        if(autoBusy || loading) return
        val c=session.experiments.current() ?: return
        if(!c.playable) {notify(c.error ?: "候補なし");return}
        if(c.comparisons.size>=3) {notify("比較履歴が3件です。「比較履歴を退避」後に選択した履歴を整理してください");return}
        if(session.experiments.candidates.sumOf {it.report.length+it.comparisons.sumOf(String::length)}>20_000_000) {notify("比較結果の保存領域が不足しています。JSONへ保存後に候補一覧を整理してください");return}
        if(needsMapping) {notify("比較前に左右の確認を行ってください");return}
        val original=session
        val output: AudioDeviceInfo;val input: AudioDeviceInfo;val config: AutoFirSettings
        try {
            output=routes.resolve(original.autoFir.output,false);input=routes.resolve(original.autoFir.input,true)
            RoutePolicy.requireAllowed(output.type,input.type)
            config=original.autoFir.copy(output=output.key(),input=input.key(),lowHz=c.settings.lowHz,highHz=c.settings.highHz,taps=c.settings.taps)
            config.validate();ExperimentalPlan.forSettings(config).validate(config.inputRate)
            ExperimentalFir.validateProcessing(requireNotNull(c.processing),c.settings,original.masterDb)
        } catch(e: Exception) {experimentalStatus="測定不能／中止: ${e.message}";return}
        engine.stop();releaseHeldRoute();autoBusy=true;experimentalPage=true;autoTerminal=false
        measurementDeviceIds=output.id to input.id;experimentalStatus="比較測定中・固定候補 ${c.id}"
        val experimentEpoch=++autoEpoch
        val run=ExperimentalRuns.start(c).put("masterDb",original.masterDb).put("settings",CalibrationRepository.settings(config))
        autoJob=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var runner: ExperimentalMeasurement?=null
            try {
                releaseJob?.join();withTimeout(5000) {while(engine.isRunning) delay(20)}
                prepareJob?.cancelAndJoin()
                val m=ExperimentalMeasurement(getApplication(),output,input,config,original,c,
                    {v->viewModelScope.launch {if(autoEpoch==experimentEpoch && autoBusy && !autoTerminal) experimentalStatus="比較測定中: $v"}},
                    {r->viewModelScope.launch {if(autoEpoch==experimentEpoch) measurementRoute=r}},run)
                runner=m
                val result=withContext(Dispatchers.Default) {m.run()}
                ensureActive();experimentalStatus="比較結果あり: ${result.getString("status")}"
            } catch(e: CancellationException) {experimentalStatus=if(e is TimeoutCancellationException) "測定不能／中止: 処理時間上限（${e.message}）" else "測定不能／中止: 取消";run.put("stage",if(e is TimeoutCancellationException) "ABORTED" else "CANCELLED").put("outcome",if(e is TimeoutCancellationException) "ABORTED" else "CANCELLED").put("status",experimentalStatus)}
            catch(e: Exception) {experimentalStatus="測定不能／中止: ${e.message}";run.put("stage","ABORTED").put("outcome","ABORTED").put("status",experimentalStatus)}
            finally {
                autoTerminal=true
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {runner?.audio?.close()}
                    val result=run.put("endedAtEpochMs",System.currentTimeMillis()).toString()
                    val next=original.copy(experiments=original.experiments.copy(candidates=original.experiments.candidates.map {
                        if(it.id==c.id) it.copy(comparisons=it.comparisons+result) else it
                    }))
                    try {next.validate();session=next;engine.settings=next;persist()}
                    catch(e: Exception) {session=original;notify("結果の保存上限: ${e.message}")}
                    autoBusy=false;autoJob=null;measurementDeviceIds=null;engine.stop();scheduleProcessing()
                }
            }
        }
    }
    /** The caller has already retained this live port after accepting a measurement result. */
    internal fun holdMeasurementRoute(port: MeasurementAudio, fir: CalibrationProfile?=null, tone: ToneProfile?=null) {
        check((fir!=null) xor (tone!=null))
        check(heldAudio==null)
        heldAudio=port;heldFirProfile=fir;heldToneProfile=tone;maintainingRoute=true
        holdJob=viewModelScope.launch {
            try {while(true) {delay(250);port.check();systemVolume=port.volumeIndex}}
            catch(e: CancellationException) {throw e}
            catch(e: Exception) {engine.stop();releaseHeldRoute();autoProgress=autoProgress.copy(detail="測定条件と異なる: ${e.message}")}
        }
    }
    fun showSavedFirResult() {session.calibration?.let {analysisJson=it.resultJson;tab=3}}
    fun reviewFirConditions() {adjustment(AdjustmentMethod.FIR);tab=3}
    fun calibrationStatus(): String {
        val applied=session.experiments.candidates.find {it.id==session.experiments.applied}
        if(session.correctionMode==CorrectionMode.FIR && applied!=null && applied.processing==session.processing)
            return "実験用FIRを手動適用中・現在の効果は未検証（${applied.id.take(12)}）"
        if(session.correctionMode==CorrectionMode.SIMPLE) {
            val p=session.toneProfile
            val volume=routes.manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            return if(soloSide==null && p?.matches(session,volume,routes.manager.mode,maintainingRoute && heldToneProfile===p)==true)
                "${p.frequency} Hz・指定条件でMIC録音上の低減を確認" else "位相・振幅: 効果未検証（条件変更・経路解除後を含む）"
        }
        if(soloSide!=null) return "一時ソロ中・低減効果は未検証"
        val p=session.calibration ?: return "校正結果なし"
        if(session.processing!=p.applied) return "FIR手動変更後・効果未検証（測定時の係数は保存済み）"
        val volume=routes.manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val reason=when {
            !maintainingRoute -> "測定経路は解除済み"
            heldFirProfile!==p -> "保持中の経路はこのFIRの検証経路ではありません"
            !CalibrationPlaybackPolicy.matches(p,session,volume,routes.manager.mode,true) -> "音量・出力・測定条件が測定時と異なります"
            else -> null
        }
        return if(reason!=null) "保存FIR・現在の効果は未検証（$reason）"
            else "${p.settings.lowHz}–${p.settings.highHz} Hz・測定時の2信号で低減確認・測定経路維持中（任意音源の効果は未評価）"
    }
    fun exportAutoResult(): String = analysisJson
    override fun onCleared() { autoJob?.cancel();heldAudio?.close();routes.close();engine.stop(); super.onCleared() }
}
