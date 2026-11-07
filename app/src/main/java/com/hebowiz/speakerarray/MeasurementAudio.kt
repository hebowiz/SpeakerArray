package com.hebowiz.speakerarray

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hebowiz.speakerarray.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.*

data class MeasurementRouteState(val requested: String="", val actual: String="未確認", val pcm: String="未確認",
    val inputDb: Double=-120.0, val clips: Long=0, val effects: String="", val frames: Long=0)
data class CaptureClock(val frame: Long, val readFrames: Int, val nanoTime: Long)

/** One uninterrupted capture and one stereo stream. Reads/writes are nonblocking with deadlines. */
class MeasurementAudio(private val context: Context, val output: AudioDeviceInfo, val input: AudioDeviceInfo,
                       val settings: AutoFirSettings, private val report: (MeasurementRouteState)->Unit) : Closeable {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    val communication=CommunicationLease(context,input)
    private val failure=AtomicReference<String?>(null)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({if(ownsFocus && it!=AudioManager.AUDIOFOCUS_GAIN) fail("音声フォーカス喪失")},main).build()
    private var track: AudioTrack?=null
    private var record: AudioRecord?=null
    private val effects=mutableListOf<AudioEffect>()
    private var effectInfo=""
    @Volatile private var closed=false
    @Volatile private var stable=false
    @Volatile private var captureFrames=0
    @Volatile var captureOriginFrames=0; private set
    private val captureLock=Any()
    @Volatile private var playback: ((FloatArray)->Boolean)?=null
    @Volatile private var playbackFailure: ((String)->Unit)?=null
    private var expectedMode=-1
    @Volatile var timestampChecks=0; private set
    @Volatile var timestampSupported=false; private set
    private var timestampFrameOrigin: Long?=null
    private val clocks=mutableListOf<CaptureClock>()
    fun captureClocks()=synchronized(clocks) {clocks.toList()}
    @Volatile var inputDb=-120.0; private set
    @Volatile var clips=0L; private set
    private var capture: FloatArray?=null
    private var reader: Thread?=null
    private var writer: Thread?=null
    private data class Write(val pcm: FloatArray, val done: CompletableDeferred<Long>)
    private val queue=LinkedBlockingQueue<Write>()
    @Volatile private var written=0L
    val writtenFrames get()=written
    private var lastReported=0L
    private var lastVolume=-1
    private var initialUnderruns=0
    private var ownsFocus=false
    val volumeIndex get()=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val mode get()=manager.mode
    private val routing=AudioRouting.OnRoutingChangedListener {if(stable && !routesMatch()) fail("入出力経路が変更されました")}
    private fun fail(reason: String) {failure.compareAndSet(null,reason);runCatching {track?.pause();track?.flush()}}
    fun check() {
        check(!closed) { "測定取消" }
        failure.get()?.let {error(it)}
        if(stable) {
            check(routesMatch()) { "この端末では指定した入出力の同時使用を確認できない: ${state().actual}" }
            check(volumeIndex==lastVolume) { "システム音量が測定中に変わりました" }
            check(communication.ready()) { "HFP通信経路が失われました" }
            check(mode==expectedMode) { "音声モードが測定条件と異なります" }
            check(track?.underrunCount==initialUnderruns) { "出力PCM欠落（underrun）" }
        }
        if(Build.VERSION.SDK_INT>=29) check(record?.activeRecordingConfiguration?.isClientSilenced!=true) { "OSによりMIC録音が無音化されています" }
    }
    fun state(): MeasurementRouteState {
        val t=track;val r=record
        return MeasurementRouteState("出力 ${output.description()} / MIC ${input.description()}",
            "出力 ${t?.routedDevice?.description() ?: "未確認"} / MIC ${r?.routedDevice?.description() ?: "未確認"}",
            "要求: 出力48000 Hz stereo float / 入力${settings.inputRate} Hz mono PCM16・preset=${settings.preset}\n実PCM: 出力${t?.sampleRate ?: "未評価"} Hz/${t?.channelCount ?: "未評価"}ch / 入力${r?.sampleRate ?: "未評価"} Hz/${r?.channelCount ?: "未評価"}ch / codec不明",
            inputDb,clips,effectInfo,captureFrames.toLong())
    }
    private fun publish() {val value=state();report(value)}
    private fun routesMatch(): Boolean {
        val out=track?.routedDevice ?: return false
        val mic=record?.routedDevice ?: return false
        return RoutePolicy.reason(out.type,mic.type)==null && out.id==output.id && mic.id==input.id
    }
    suspend fun open() {
        RoutePolicy.requireAllowed(output.type,input.type)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "MIC権限が必要です" }
        if(communication.hfp && Build.VERSION.SDK_INT>=31) check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED) { "Bluetooth権限が必要です" }
        check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "音声フォーカスを取得できません" }
        ownsFocus=true
        communication.request()
        val deadline=System.nanoTime()+8_000_000_000L
        while(!communication.ready()) {coroutineContext.ensureActive();check();check(System.nanoTime()<deadline) {"HFP確立タイムアウト"};delay(20)}
        if(settings.preset==MediaRecorder.AudioSource.UNPROCESSED) check(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)=="true") { "UNPROCESSED非対応です。録音プリセットを変更してください" }
        val minIn=AudioRecord.getMinBufferSize(settings.inputRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        check(minIn>0) {"入力PCM形式非対応"}
        val r=AudioRecord.Builder().setAudioSource(settings.preset).setAudioFormat(AudioFormat.Builder().setSampleRate(settings.inputRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(max(minIn*4,settings.inputRate/2)).build()
        record=r
        check(r.state==AudioRecord.STATE_INITIALIZED && r.sampleRate==settings.inputRate) {"録音初期化失敗"}
        check(r.setPreferredDevice(input)) {"MIC要求が拒否されました"}
        val notes=mutableListOf<String>()
        fun effect(name: String, available: Boolean, create: ()->AudioEffect?) {
            if(!available) {notes+="$name API非対応";return}
            runCatching {create()?.let {effects+=it;val result=it.setEnabled(false);notes+="$name 無効化=${result==AudioEffect.SUCCESS && !it.enabled}"}}
                .onFailure {notes+="$name 無効化失敗"}
        }
        effect("AGC",AutomaticGainControl.isAvailable()) {AutomaticGainControl.create(r.audioSessionId)}
        effect("NS",NoiseSuppressor.isAvailable()) {NoiseSuppressor.create(r.audioSessionId)}
        effect("AEC",AcousticEchoCanceler.isAvailable()) {AcousticEchoCanceler.create(r.audioSessionId)}
        effectInfo=notes.joinToString(" / ")+"。MIC内部処理は不明"
        val minOut=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT)
        check(minOut>0) {"48 kHz stereo出力非対応"}
        val t=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(max(minOut*4,SAMPLE_RATE*8/5)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track=t
        check(t.state==AudioTrack.STATE_INITIALIZED && t.channelCount==2 && t.setPreferredDevice(output)) {"ステレオ出力要求が拒否されました"}
        t.addOnRoutingChangedListener(routing,main);r.addOnRoutingChangedListener(routing,main)
        val expectedSeconds=ceil(8*(settings.seconds+2*settings.tailSeconds+settings.latencySeconds+1.0)+20).toInt()
        val captureSeconds=min(MeasurementLimits.MAX_SECONDS,expectedSeconds)
        // Capture + two analysis snapshots + FFT scratch must fit a bounded working set.
        val estimatedBytes=captureSeconds.toLong()*(settings.inputRate*12L+SAMPLE_RATE*8L)+48L*1024*1024
        check(estimatedBytes < Runtime.getRuntime().maxMemory()*0.65) { "測定メモリ上限を超えます。測定時間・残響・探索余裕を短くしてください" }
        capture=FloatArray(settings.inputRate*captureSeconds)
        AudioResourceUsage.recordingStarts.incrementAndGet()
        r.startRecording();check(r.recordingState==AudioRecord.RECORDSTATE_RECORDING)
        reader=Thread({readLoop(r)},"AutoFIR-capture").also {it.start()}
        t.play()
        writer=Thread({writeLoop(t)},"AutoFIR-output").also {it.start()}
        var matchingSince=0L
        val streamDeadline=System.nanoTime()+5_000_000_000L
        while(true) {
            write(FloatArray(BLOCK*2))
            val now=System.nanoTime()
            val actualOut=t.routedDevice;val actualIn=r.routedDevice
            if(actualOut!=null && actualIn!=null) RoutePolicy.requireAllowed(actualOut.type,actualIn.type)
            if(routesMatch()) {if(matchingSince==0L) matchingSince=now;if(now-matchingSince>300_000_000L && captureFrames>0) break} else matchingSince=0
            if(now>streamDeadline) {publish();error("この端末では指定した入出力の同時使用を確認できない: ${state().actual}")}
        }
        lastVolume=volumeIndex;initialUnderruns=t.underrunCount;expectedMode=mode;stable=true;publish()
    }
    private fun readLoop(r: AudioRecord) {
        val pcm=ShortArray(1024);var last=System.nanoTime()
        try {
            while(!closed) {
                val n=r.read(pcm,0,pcm.size,AudioRecord.READ_NON_BLOCKING)
                check(n>=0) {"録音PCMエラー $n"}
                if(n==0) {check(System.nanoTime()-last<2_000_000_000L) {"録音PCM受信停止"};Thread.sleep(3);continue}
                last=System.nanoTime()
                synchronized(captureLock) {
                    val dest=capture
                    if(dest!=null) {
                        val offset=captureFrames-captureOriginFrames
                        check(offset+n<=dest.size) {"録音メモリの上限時間を超えました"}
                        for(i in 0 until n) dest[offset+i]=pcm[i]/32768f
                    }
                    captureFrames+=n
                }
                var square=0.0
                for(i in 0 until n) {val v=pcm[i]/32768.0;square+=v*v;if(abs(v)>=0.999) clips++}
                inputDb=levelDb(sqrt(square/n))
                if(stable && captureFrames%settings.inputRate<n) {
                    val stamp=AudioTimestamp()
                    if(r.getTimestamp(stamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS) {
                        timestampSupported=true;timestampChecks++
                        // Timestamp is at the record server, ahead of unread buffered PCM.
                        // A backwards timestamp is conclusive; forward loss is also checked acoustically.
                        timestampFrameOrigin?.let {check(stamp.framePosition>=it) {"録音フレーム時刻が逆行しました"}}
                        timestampFrameOrigin=stamp.framePosition
                        synchronized(clocks) {if(clocks.size<256) clocks+=CaptureClock(stamp.framePosition,captureFrames,stamp.nanoTime)}
                    }
                }
                if(stable && clips>0) error("MIC入力クリップ")
                check()
                if(System.nanoTime()-lastReported>200_000_000L) {lastReported=System.nanoTime();publish()}
            }
        } catch(e: Exception) {if(!closed) fail(e.message ?: "録音失敗")}
    }
    private fun writeLoop(t: AudioTrack) {
        val zero=FloatArray(BLOCK*2)
        var active: Write?=null
        try {
            while(!closed) {
                active=queue.poll()
                if(active==null) {
                    zero.fill(0f)
                    playback?.let {render ->
                        try {if(!render(zero)) playback=null;Unit}
                        catch(e: Exception) {zero.fill(0f);playback=null;playbackFailure?.invoke(e.message ?: "再生停止")}
                    }
                }
                val pcm=active?.pcm ?: zero
                var offset=0;var progress=System.nanoTime();val start=written
                while(offset<pcm.size && !closed) {
                    check()
                    val n=t.write(pcm,offset,min(BLOCK*2,pcm.size-offset),AudioTrack.WRITE_NON_BLOCKING)
                    check(n>=0 && n%2==0) {"出力PCMエラー $n"}
                    if(n==0) {check(System.nanoTime()-progress<2_000_000_000L) {"出力PCM停止"};Thread.sleep(2)}
                    else {offset+=n;written+=n/2;progress=System.nanoTime()}
                }
                active?.done?.complete(start);active=null
            }
        } catch(e: Exception) {active?.done?.completeExceptionally(e);playbackFailure?.invoke(e.message ?: "出力失敗");if(!closed) fail(e.message ?: "出力失敗")}
        finally {while(true) {val item=queue.poll() ?: break;item.done.completeExceptionally(IllegalStateException("出力停止"))}}
    }
    suspend fun write(pcm: FloatArray): Long {
        coroutineContext.ensureActive();check()
        val job=Write(pcm,CompletableDeferred());queue.put(job)
        while(!job.done.isCompleted) {coroutineContext.ensureActive();check();delay(5)}
        return job.done.await()
    }
    suspend fun silence(seconds: Double) {repeat(ceil(seconds*SAMPLE_RATE/BLOCK).toInt()) {write(FloatArray(BLOCK*2))}}
    fun snapshot(): DoubleArray = synchronized(captureLock) {check();val count=captureFrames-captureOriginFrames;val data=checkNotNull(capture);DoubleArray(count) {data[it].toDouble()}}
    fun discardCaptured() = synchronized(captureLock) {check();captureOriginFrames=captureFrames}
    val capturedFrames get() = captureFrames.toLong()
    fun captureRange(from: Long, count: Int): DoubleArray = synchronized(captureLock) {
        check();require(from>=captureOriginFrames && from+count<=captureFrames) {"録音フレーム範囲不足"}
        val data=checkNotNull(capture);val offset=(from-captureOriginFrames).toInt()
        DoubleArray(count) {data[offset+it].toDouble()}
    }
    fun beginPlayback(render: (FloatArray)->Boolean, failed: (String)->Unit) {
        check();check(playback==null);playbackFailure=failed;playback=render
    }
    fun endPlayback() {playback=null;playbackFailure=null}
    /** Keep HFP/recording conditions alive after acceptance. No microphone monitoring. */
    fun retain() {
        synchronized(captureLock) {capture=null}
        if(ownsFocus) {ownsFocus=false;manager.abandonAudioFocusRequest(focus)}
    }
    override fun close() {
        if(closed) return
        closed=true;stable=false
        playbackFailure?.invoke("測定経路を解放しました");endPlayback()
        runCatching {track?.pause();track?.flush()};runCatching {record?.stop()}
        reader?.join(1000);writer?.join(1000)
        track?.let {runCatching {it.removeOnRoutingChangedListener(routing);it.release()}};track=null
        record?.let {runCatching {it.removeOnRoutingChangedListener(routing);it.release()}};record=null
        effects.forEach {runCatching {it.release()}};effects.clear()
        try {communication.close()} finally {if(ownsFocus) {ownsFocus=false;manager.abandonAudioFocusRequest(focus)};capture=null}
    }
}
