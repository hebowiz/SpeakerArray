package com.hebowiz.speakerarray

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.*
import java.io.File

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
        prefs.getString("last", null)?.let { raw -> runCatching { SessionRepository.decode(raw) }.onSuccess { session = it }.onFailure { message = "保存設定を復元できません: ${it.message}" } }
        draft = channel().fir
        scheduleProcessing(); previewDraft()
        if (session.source.wavUri.isNotBlank()) loadWav(Uri.parse(session.source.wavUri), false)
    }
    fun notify(text: String) { message = text }
    fun dismissMessage() { message = "" }
    fun channel() = if (sideRight) session.processing.right else session.processing.left
    fun update(change: (Session) -> Session) {
        try {
            val next = change(session); next.validate()
            val old = session
            session = next; engine.settings = next
            if (old.processing != next.processing) scheduleProcessing() else preview()
            persist()
        } catch (e: Exception) { message = e.message ?: "設定が不正です" }
    }
    fun processing(change: (Processing) -> Processing) = update { it.copy(processing=change(it.processing)) }
    fun channel(right: Boolean, change: (Channel) -> Channel) = processing { if (right) it.copy(right=change(it.right)) else it.copy(left=change(it.left)) }
    fun source(change: (SourceSettings) -> SourceSettings) {
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
        if (loading || prepared?.generation != generation) { message = "設定の準備が完了するまでお待ちください"; return }
        if (session.source.kind == SourceKind.WAV && wavFile == null) { message = "WAVを選択してください"; return }
        engine.settings = session; engine.start(prepared!!, wavFile)
    }
    fun stop() { engine.stop() }
    fun background() { engine.stop("バックグラウンド移行・停止"); persist() }
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
    override fun onCleared() { engine.stop(); super.onCleared() }
}
