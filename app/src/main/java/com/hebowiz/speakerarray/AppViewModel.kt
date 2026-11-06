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
    var loading by mutableStateOf(false); private set
    var generation by mutableLongStateOf(0); private set
    var autoBusy by mutableStateOf(false); private set
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
        measurementDeviceIds?.let {(output,input)->
            if(autoBusy && (routes.devices(false).none {it.id==output} || routes.devices(true).none {it.id==input})) cancelAuto("選択したデバイスが切断されました")
        }
    }
    private var autoJob: Job? = null
    private var heldAudio: MeasurementAudio? = null
    private var holdJob: Job? = null
    private var releaseJob: Job? = null
    private var autoEpoch = System.currentTimeMillis()
    private var autoBackup: Session? = null
    private var prepared: PreparedProcessing? = null
    private var prepareJob: Job? = null
    private var draftJob: Job? = null
    private var saveJob: Job? = null
    private var previewJob: Job? = null
    private var wavJob: Job? = null
    private var wavGeneration = 0L
    private var wavFile: File? = null
    private var wavKey = ""
    private val cacheCleanup = viewModelScope.async(Dispatchers.IO) {
        // Only this app's generated WAV caches; no user-selected documents are deleted.
        app.cacheDir.listFiles()?.filter { it.isFile && (it.name.startsWith("wav-import") || it.name.startsWith("wav-48000")) }?.forEach { it.delete() }
    }
    private var soloSaved: Pair<Boolean, Boolean>? = null
    private var soloSide: Boolean? = null
    private var audibleProcessing = Processing()
    private val generations = linkedMapOf<Long,Processing>()
    val engine = AudioEngine(app, ::audioReport, { needsMapping = true })
    private fun audioReport(status: AudioStatus) {
        val changed = audio.generation != status.generation || audio.playing != status.playing
        audio = status
        generations[status.generation]?.let { audibleProcessing = it }
        if (changed) { generations.keys.removeAll { it < status.generation }; preview() }
    }
    init {
        autoDiagnostic=prefs.getString("autoDiagnostic","") ?: ""
        analysisJson=autoDiagnostic
        runCatching {
            if(autoDiagnostic.isNotEmpty()) {
                val diagnostic=JSONObject(autoDiagnostic)
                val stage=AutoStage.valueOf(diagnostic.getString("stage"))
                autoProgress=AutoProgress(if(stage==AutoStage.APPLIED) AutoStage.IDLE else stage,
                    detail=if(stage==AutoStage.APPLIED) "保存結果あり・測定経路は解除済み。再測定が必要です" else diagnostic.getString("detail"))
            }
        }
        prefs.getString("last", null)?.let { raw -> runCatching { SessionRepository.decode(raw) }.onSuccess { session = it }.onFailure { message = "保存設定を復元できません: ${it.message}" } }
        draft = channel().fir
        scheduleProcessing(); previewDraft()
        if (session.source.wavUri.isNotBlank()) loadWav(Uri.parse(session.source.wavUri), false)
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
            if(old.calibration!=null && old!=next && engine.isRunning) engine.stop("校正条件変更・停止")
            session = next; engine.settings = next
            if (old.processing != next.processing) scheduleProcessing() else preview()
            persist()
        } catch (e: Exception) { message = e.message ?: "設定が不正です" }
    }
    fun processing(change: (Processing) -> Processing) = update { it.copy(processing=change(it.processing)) }
    fun channel(right: Boolean, change: (Channel) -> Channel) = processing { if (right) it.copy(right=change(it.right)) else it.copy(left=change(it.left)) }
    fun source(change: (SourceSettings) -> SourceSettings) {
        if(autoBusy) return
        val old = session.source
        val next = change(old)
        runCatching { next.validate() }.onFailure { notify(it.message ?: "音源設定が不正です"); return }
        if (old.mono != next.mono && next.wavUri.isNotBlank()) {
            loadWav(Uri.parse(next.wavUri), false, next.mono)
            return
        }
        if (old.copy(frequency=next.frequency) != next) stop()
        update { it.copy(source=next) }
    }
    fun start() {
        if(autoBusy) return
        if(releaseJob?.isActive==true) {message="通信経路の解放完了までお待ちください";return}
        try {
            val profile=session.calibration
            if(profile!=null && session.processing==profile.applied) {
                check(maintainingRoute) { "HFP校正時のMIC・通信経路が解除されています。再測定または元に戻す操作が必要です" }
                heldAudio?.check()
            }
            engine.requestedOutput=routes.resolve(session.autoFir.output,false)
        } catch(e: Exception) {message=e.message ?: "出力先を選択してください";return}
        if (loading || prepared?.generation != generation) { message = "設定の準備が完了するまでお待ちください"; return }
        if (session.source.kind == SourceKind.WAV && wavFile == null) { message = "WAVを選択してください"; return }
        engine.settings = session
        try {
            if(session.calibration!=null && session.processing==session.calibration!!.applied) engine.startRetained(prepared!!,wavFile,requireNotNull(heldAudio))
            else engine.start(prepared!!, wavFile)
        } catch(e: Exception) {message=e.message ?: "再生開始失敗"}
    }
    fun stop() { engine.stop(); if(autoBusy) cancelAuto("停止操作") }
    fun background() { cancelAuto("バックグラウンド移行"); releaseHeldRoute(); engine.stop("バックグラウンド移行・停止"); persist() }
    fun solo(right: Boolean) {
        if (soloSaved != null && soloSide == right) {
            val (l,r) = soloSaved!!; soloSaved = null
            soloSide = null
            processing { it.copy(left=it.left.copy(muted=l), right=it.right.copy(muted=r)) }
        } else {
            if (soloSaved == null) soloSaved = session.processing.let { it.left.muted to it.right.muted }
            soloSide = right
            processing { it.copy(left=it.left.copy(muted=right), right=it.right.copy(muted=!right)) }
        }
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
        processing { p -> p.copy(left=if (!right || both) p.left.copy(fir=spec) else p.left, right=if (right || both) p.right.copy(fir=spec) else p.right) }
        firPending = false
    }
    fun cancelFir() { selectSide(sideRight) }
    fun copyBoth(fromRight: Boolean) {
        val original = if (fromRight) session.processing.right else session.processing.left
        processing { it.copy(left=it.left.copy(fir=original.fir,enabled=original.enabled),right=it.right.copy(fir=original.fir,enabled=original.enabled)) }; selectSide(sideRight)
    }
    fun length(n: Int) {
        if (audio.playing) { message = "FIR長の変更は停止後に行ってください"; return }
        try {
            val p = session.processing.copy(length=n); p.validate()
            update { it.copy(processing=p,a=null,b=null) }; selectSide(sideRight)
        } catch (e: Exception) { message = "FIR長を変更できません: ${e.message}" }
    }
    fun storeAB(b: Boolean) { update { if (b) it.copy(b=it.processing) else it.copy(a=it.processing) }; message = "${if(b) "B" else "A"}に保存しました" }
    fun recallAB(b: Boolean) {
        val p = if (b) session.b else session.a
        if (p == null) { message = "比較設定が未保存です"; return }
        processing { p }; selectSide(sideRight)
    }
    private fun scheduleProcessing() {
        val snapshot = session; val id = ++generation
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            delay(20)
            try {
                val value = withContext(Dispatchers.Default) { PreparedProcessing.create(snapshot.processing, id) }
                ensureActive(); prepared = value; generations[id] = snapshot.processing
                if (!audio.playing) { audibleProcessing = snapshot.processing; generations.keys.removeAll { it < id } }
                engine.submit(value); preview()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "処理設定エラー: ${e.message}" }
        }
        engine.settings = session
        preview()
    }
    fun preview() {
        previewJob?.cancel()
        val s = if(audio.playing) session.copy(processing=audibleProcessing) else session
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
        saveJob = viewModelScope.launch(Dispatchers.IO) { delay(250); prefs.edit().putString("last",SessionRepository.encode(snapshot)).apply() }
    }
    fun loadSession(text: String) {
        if(autoBusy) return
        try {
            val s = SessionRepository.decode(text)
            wavJob?.cancel(); wavGeneration++; loading = false
            stop(); soloSaved = null; needsMapping = true; update { s }; selectSide(false)
            wavFile = null; wavKey = ""
            if (s.source.wavUri.isNotBlank()) loadWav(Uri.parse(s.source.wavUri), false)
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
    fun loadWav(uri: Uri, persistPermission: Boolean = true, mono: MonoMode = session.source.mono) {
        if(autoBusy) return
        stop(); wavJob?.cancel(); loading = true
        val request = ++wavGeneration
        val key = "$uri/$mono"
        wavJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                cacheCleanup.await()
                if (persistPermission) app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (wavKey != key || wavFile?.exists() != true) {
                    val file = withContext(Dispatchers.IO) {
                        val input = File.createTempFile("wav-import", ".wav",app.cacheDir)
                        val output = File.createTempFile("wav-48000", ".pcm",app.cacheDir)
                        try {
                            app.contentResolver.openInputStream(uri)!!.use { src -> input.outputStream().use { dest ->
                                val bytes = ByteArray(65536); var total = 0L
                                while (true) { ensureActive(); val n = src.read(bytes); if(n < 0) break; total += n; require(total <= 1_073_741_824L) { "WAVの読込上限は1 GiBです" }; dest.write(bytes,0,n) }
                            } }
                            WavDecoder.convert(input,output,mono); output
                        } catch(e: Exception) { output.delete(); throw e } finally { input.delete() }
                    }
                    ensureActive()
                    val previous = wavFile
                    wavFile = file; wavKey = key
                    viewModelScope.launch(Dispatchers.IO) { previous?.delete() }
                }
                update { it.copy(source=it.source.copy(wavUri=uri.toString(),mono=mono)) }
                message = "WAVを48 kHzで準備しました。再生は停止中です"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message = "WAVを読み込めません。参照切れの場合は再選択してください: ${e.message}" }
            finally { if (request == wavGeneration) loading = false }
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
            autoProgress=AutoProgress(AutoStage.IDLE,detail="係数は保存済み・測定経路は解除されました。校正条件を再確認してください")
        holdJob?.cancel();holdJob=null
        val held=heldAudio;heldAudio=null;maintainingRoute=false
        if(held!=null) releaseJob=viewModelScope.launch(NonCancellable+Dispatchers.IO) {held.close()}
    }
    fun undoAuto() {
        if(autoBusy) return
        val backup=autoBackup ?: session.calibration?.let {runCatching {SessionRepository.decode(it.backup)}.getOrNull()} ?: return
        stop();releaseHeldRoute();update {backup};selectSide(sideRight);autoBackup=null
        autoProgress=AutoProgress(AutoStage.IDLE,detail="元のFIR・FIR長・A/B・音源・音量・ルートを復元しました")
    }
    fun applySuggestedBand() {
        if(autoBusy) return
        val suggestion=runCatching {JSONObject(analysisJson).optJSONObject("quality")?.optJSONObject("suggestion")}.getOrNull() ?: return
        autoSettings {it.copy(lowHz=suggestion.getDouble("lowHz"),highHz=suggestion.getDouble("highHz"))}
        startAuto()
    }
    fun startAuto() {
        if(autoBusy || loading) return
        if(needsMapping) {message="測定前に「再生・調整」の「左右の確認」を行ってください";return}
        val backup=session
        val output: AudioDeviceInfo;val input: AudioDeviceInfo
        try {backup.autoFir.validate();output=routes.resolve(backup.autoFir.output,false);input=routes.resolve(backup.autoFir.input,true)}
        catch(e: Exception) {message=e.message ?: "デバイス未確認";return}
        releaseHeldRoute();engine.stop();autoBusy=true;autoResult=null;autoDiagnostic="";analysisJson=""
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
                    {p->viewModelScope.launch {if(autoEpoch==id && autoBusy) autoProgress=p}},
                    {r->viewModelScope.launch {if(autoEpoch==id) measurementRoute=r}},
                    {json->viewModelScope.launch {if(autoEpoch==id) analysisJson=json}})
                val runner=measurement
                val result=withContext(Dispatchers.Default) {runner.run()}
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
                val ready=withContext(Dispatchers.Default) {PreparedProcessing.create(next.processing,generation+1)}
                ensureActive();runner.audio.check()
                runner.audio.retain()
                // Publish one accepted generation atomically. Trial processing never mutates session.
                generation=ready.generation;prepared=ready;audibleProcessing=next.processing
                session=next;engine.settings=next;autoBackup=backup;autoResult=result;systemVolume=result.volume
                draft=channel().fir;firPending=false;preview();previewDraft();persist()
                autoProgress=AutoProgress(AutoStage.APPLIED,1f,"${resolved.lowHz}–${resolved.highHz} Hz・${if(profile.hfp) "HFP録音上" else "MIC録音上"}で2信号の低減確認・停止中${if(result.verification.any {it.noiseLimited}) "（ノイズ床による測定限界あり）" else ""}")
                run {
                    heldAudio=runner.audio;maintainingRoute=true;retained=true
                    holdJob=viewModelScope.launch {
                        try {while(true) {delay(250);runner.audio.check();systemVolume=runner.audio.volumeIndex}}
                        catch(e: CancellationException) {throw e}
                        catch(e: Exception) {engine.stop();releaseHeldRoute();autoProgress=autoProgress.copy(detail="測定条件と異なる: ${e.message}")}
                    }
                }
            } catch(e: CancellationException) {transaction.rollback();autoProgress=AutoProgress(AutoStage.CANCELLED,detail=e.message ?: "取消")}
            catch(e: Exception) {transaction.rollback();autoProgress=AutoProgress(AutoStage.REJECTED,detail=e.message ?: "測定失敗")}
            finally {
                withContext(NonCancellable) {
                try {
                if(autoEpoch==id) {
                    autoDiagnostic=(measurement?.finalDiagnostics() ?: JSONObject().put("type","speakerarray-diagnostic").put("schemaVersion",2))
                        .put("generation",id).put("stage",autoProgress.stage.name).put("detail",autoProgress.detail)
                        .put("requested",measurementRoute.requested).put("actual",measurementRoute.actual)
                        .put("pcm",measurementRoute.pcm).put("effects",measurementRoute.effects)
                        .put("recordedFrames",measurementRoute.frames).put("clips",measurementRoute.clips)
                        .put("settings",CalibrationRepository.settings(resolved)).put("masterDb",backup.masterDb)
                        .apply {if(!has("reasonCode")) put("reasonCode",if(autoProgress.stage==AutoStage.CANCELLED) "CANCELLED" else MeasurementResultStore.failureCode(autoProgress.detail))}.toString(2)
                    analysisJson=autoDiagnostic
                    val savedDiagnostic=autoDiagnostic
                    withContext(Dispatchers.IO) {prefs.edit().putString("autoDiagnostic",savedDiagnostic).apply()}
                }
                } finally {
                withContext(Dispatchers.IO) {if(!retained) measurement?.audio?.close()}
                if(autoEpoch==id) {autoBusy=false;autoJob=null;measurementDeviceIds=null;if(autoProgress.stage!=AutoStage.APPLIED) {scheduleProcessing();previewDraft()}}
                }
                }
            }
        }
    }
    fun calibrationStatus(): String {
        val p=session.calibration ?: return "校正結果なし"
        val volume=routes.manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return if(!CalibrationPlaybackPolicy.matches(p,session,volume,routes.manager.mode,maintainingRoute)) "測定条件と異なる・経路解除後は再測定が必要です" else "測定経路を維持中・任意音源の効果は未評価"
    }
    fun exportAutoResult(): String = autoDiagnostic
    override fun onCleared() { autoJob?.cancel();heldAudio?.close();routes.close();engine.stop(); super.onCleared() }
}
