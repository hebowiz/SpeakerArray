package com.hebowiz.speakerarray

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.hebowiz.speakerarray.core.*
import java.io.File
import kotlin.math.*

data class AudioStatus(val playing: Boolean = false, val switching: Boolean = false, val generation: Long = 0,
    val leftDb: Double = -120.0, val rightDb: Double = -120.0, val clips: Long = 0,
    val underruns: Int = 0, val wavUnderruns: Long = 0, val cpuPercent: Double = 0.0,
    val route: String = "停止中・再生時に実ルートを取得", val position: Double = 0.0, val message: String = "停止")

class AudioEngine(context: Context, private val report: (AudioStatus) -> Unit, private val routeChanged: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ if (it != AudioManager.AUDIOFOCUS_GAIN) stop("音声フォーカス喪失・停止") }, main)
        .setWillPauseWhenDucked(true).build()
    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var reason = "停止"
    @Volatile private var dsp: DspEngine? = null
    @Volatile private var wav: WavSource? = null
    @Volatile var settings = Session()
    @Volatile var requestedOutput: AudioDeviceInfo? = null
    @Volatile private var retainedActive=false
    val isRunning get() = worker != null || retainedActive
    private var routeId: Int? = null
    private var routeName = "出力先を確認中"
    @Volatile var soloSide: Boolean? = null
    fun submit(p: PreparedProcessing) {
        if(stopRequested && isRunning) return
        check(CalibrationPlaybackPolicy.peakBound(p.parameters,settings.masterDb)<=0.9) {"出力ピーク上限を超える設定です。共通出力を下げてください"}
        dsp?.submit(p)
    }
    fun seek(fraction: Double) { wav?.seek(fraction) }
    fun start(prepared: PreparedProcessing, wavFile: File?) {
        if (isRunning) return
        check(CalibrationPlaybackPolicy.peakBound(prepared.parameters,settings.masterDb)<=0.9) {"出力ピーク上限を超える設定です。共通出力を下げてください"}
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            report(AudioStatus(message="音声フォーカスを取得できません")); return
        }
        val initial = settings
        stopRequested = false; reason = "停止"
        val engine = DspEngine(prepared); dsp = engine
        report(AudioStatus(playing=true,switching=engine.switching,generation=engine.appliedGeneration,message="再生準備中"))
        worker = Thread({ run(initial, engine, wavFile) }, "SpeakerArray-audio").also { it.start() }
    }
    fun stop(message: String = "停止") { reason = message; stopRequested = true }
    /** Use the exact measured AudioTrack, attributes and communication lease; no track migration. */
    fun startRetained(prepared: PreparedProcessing, wavFile: File?, port: MeasurementAudio) {
        if(isRunning) return
        port.check()
        val initial=settings;val profile=initial.calibration
        check(initial.autoFir==port.settings) {"測定の共通条件が一致しません"}
        if(initial.correctionMode==CorrectionMode.FIR && profile!=null) {
            check(CalibrationPlaybackPolicy.matches(profile,initial,port.volumeIndex,port.mode,true)) {"校正条件が一致しません"}
            check(prepared.parameters==profile.applied.withSolo(soloSide)) {"検証済み係数と再生係数が異なります"}
        }
        check(CalibrationPlaybackPolicy.peakBound(prepared.parameters,initial.masterDb)<=0.9) {"出力ピーク上限を超えます。共通出力を下げてください"}
        check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"音声フォーカスを取得できません"}
        val fileSource=if(initial.source.kind==SourceKind.WAV) WavSource(requireNotNull(wavFile),initial.source.loop,initial.source.loopFade) else null
        val source=SourceEngine(initial.source,fileSource)
        val engine=DspEngine(prepared);dsp=engine;wav=fileSource
        val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK)
        var gain=0.0;var lastReport=0L
        stopRequested=false;reason="停止";retainedActive=true
        fun finish(detail: String) {
            port.endPlayback();fileSource?.close();wav=null;dsp=null;retainedActive=false
            manager.abandonAudioFocusRequest(focus)
            main.post {report(AudioStatus(generation=prepared.generation,message=detail,route="測定と同一の出力ストリーム（停止中）"))}
        }
        try {
            port.beginPlayback({out ->
                check((settings.correctionMode!=CorrectionMode.FIR || profile==null || CalibrationPlaybackPolicy.matches(profile,settings,port.volumeIndex,port.mode,true)) && settings.masterDb==initial.masterDb && settings.swapped==initial.swapped && settings.autoFir==initial.autoFir) {"校正処理が変更されました"}
                port.check()
                if(stopRequested && gain<=0) {finish(reason);false} else {
                    source.render(l,r,settings.source.frequency,0.0,quadrature=prepared.sineQuadrature)
                    check(fileSource?.error==null) {"WAV読込エラー"}
                    check((l.asSequence()+r.asSequence()).all {it.isFinite() && abs(it)<=1.0}) {"音源ピークが検証範囲を超えました"}
                    engine.process(l,r,out,initial.masterDb,initial.swapped)
                    check(engine.clips==0L) {"再生ピーク上限を超えました"}
                    for(i in 0 until BLOCK) {
                        gain=(gain+(if(stopRequested) -1 else 1)/480.0).coerceIn(0.0,1.0)
                        out[2*i]=(out[2*i]*gain).toFloat();out[2*i+1]=(out[2*i+1]*gain).toFloat()
                    }
                    if(source.finished) stop("音源終了・停止")
                    if(System.nanoTime()-lastReport>100_000_000L) {
                        lastReport=System.nanoTime()
                        val status=AudioStatus(playing=true,switching=engine.switching,generation=engine.appliedGeneration,
                            leftDb=levelDb(l.maxOf {abs(it)}),rightDb=levelDb(r.maxOf {abs(it)}),
                            route=port.state().actual,message=if(settings.correctionMode==CorrectionMode.SIMPLE) "位相・振幅（効果未検証）・測定経路維持中" else "検証と同一ストリームで再生（任意音源の効果は未評価）")
                        main.post {report(status)}
                    }
                    true
                }
            },{finish("停止: $it")})
        } catch(e: Exception) {finish("停止: ${e.message}");throw e}
    }
    private fun run(initial: Session, engine: DspEngine, wavFile: File?) {
        var track: AudioTrack? = null
        var finalMessage: String
        var lastStatus = AudioStatus()
        val routing = AudioRouting.OnRoutingChangedListener { routing ->
            val device = routing.routedDevice ?: return@OnRoutingChangedListener
            val old = routeId; routeId = device.id
            routeName = device.description()
            if (old != null && old != device.id) { stop("出力先変更・停止"); routeChanged() }
        }
        try {
            val fileSource = if (initial.source.kind == SourceKind.WAV) WavSource(requireNotNull(wavFile) { "WAVを選択してください" }, initial.source.loop, initial.source.loopFade) else null
            wav = fileSource
            val source = SourceEngine(initial.source, fileSource)
            val left = DoubleArray(BLOCK); val right = DoubleArray(BLOCK); val out = FloatArray(BLOCK*2); val envelope = DoubleArray(BLOCK)
            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0) { "48 kHz ステレオfloat出力に対応していません" }
            track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(max(minimum*4, BLOCK*8*8)).build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack初期化失敗" }
            val requested = requireNotNull(requestedOutput) { "出力デバイス未選択" }
            check(track.setPreferredDevice(requested)) { "出力デバイス要求が拒否されました" }
            routeId = null
            track.addOnRoutingChangedListener(routing, main)
            track.play()
            val routingDeadline = System.nanoTime()+5_000_000_000L
            val silence = FloatArray(BLOCK*2)
            while (track.routedDevice?.id != requested.id) {
                check(!stopRequested) { reason }
                check(System.nanoTime()<routingDeadline) { "要求した出力先を確認できません" }
                StereoWriter.writeAll(silence) { a,o,n -> track.write(a,o,n,AudioTrack.WRITE_BLOCKING) }
            }
            routeId=requested.id;routeName=requested.description()
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var gain = 0.0; var started = false; var lastReport = 0L; var worstCpu = 0.0
            var peakL = 0.0; var peakR = 0.0
            while (!stopRequested || gain > 0.0) {
                check(track.routedDevice?.id==requested.id) { "出力先不一致・停止" }
                val begin = System.nanoTime()
                val s = settings
                source.render(left, right, s.source.frequency, 0.0, quadrature=initial.source.kind==SourceKind.SINE)
                check(left.indices.all {left[it].isFinite() && right[it].isFinite() && abs(left[it])<=1.0 && abs(right[it])<=1.0}) {"音源ピークが1を超えています。音源の振幅を下げてください"}
                if (source.finished) stop("音源終了・停止")
                if (fileSource?.error != null) error(fileSource.error!!)
                for (i in envelope.indices) { gain = (gain+(if (stopRequested) -1 else 1)/480.0).coerceIn(0.0, 1.0); envelope[i] = gain }
                engine.process(left, right, out, s.masterDb, s.swapped, envelope)
                check(engine.clips==0L) {"出力ピーク上限を超えました。共通出力を下げてください"}
                peakL = max(peakL, engine.peakLeft); peakR = max(peakR, engine.peakRight)
                worstCpu = max(worstCpu, (System.nanoTime()-begin).toDouble()/(BLOCK*1e9/SAMPLE_RATE)*100)
                val audioTrack = track
                StereoWriter.writeAll(out) { values, offset, size -> audioTrack.write(values, offset, size, AudioTrack.WRITE_BLOCKING) }
                if (!started) { track.play(); started = true }
                if (System.nanoTime()-lastReport >= 100_000_000) {
                    val status = AudioStatus(true, engine.switching, engine.appliedGeneration, levelDb(peakL), levelDb(peakR), engine.clips,
                        track.underrunCount, fileSource?.underruns ?: 0, worstCpu, routeName,
                        fileSource?.let { it.positionFrames.toDouble()/it.totalFrames } ?: 0.0,
                        if (stopRequested) "停止処理中" else if (engine.switching) "設定切替中・非定常" else "再生")
                    lastStatus = status
                    main.post { report(status) }; lastReport = System.nanoTime(); peakL = 0.0; peakR = 0.0; worstCpu = 0.0
                }
            }
            // Blocking zero writes let the short output fade reach the device before flushing tails.
            out.fill(0f)
            repeat(8) { StereoWriter.writeAll(out) { a, o, n -> track.write(a, o, n, AudioTrack.WRITE_BLOCKING) } }
            finalMessage = reason
        } catch (e: Exception) { finalMessage = "停止: ${e.message}" }
        finally {
            wav?.close(); wav = null
            track?.let { t -> runCatching { t.removeOnRoutingChangedListener(routing); t.pause(); t.flush(); t.release() } }
            dsp = null
            manager.abandonAudioFocusRequest(focus)
        }
        main.post { worker = null; report(lastStatus.copy(playing=false,switching=false,leftDb=-120.0,rightDb=-120.0,generation=engine.appliedGeneration, clips=engine.clips, route="$routeName（停止中・前回）", message=finalMessage)) }
    }
}
