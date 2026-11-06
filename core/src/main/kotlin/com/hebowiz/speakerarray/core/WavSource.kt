package com.hebowiz.speakerarray.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class WavInfo(val format: Int, val channels: Int, val rate: Int, val bits: Int, val dataOffset: Long, val dataBytes: Long) {
    val frameBytes get() = channels*bits/8
    val frames get() = dataBytes/frameBytes
}
object WavDecoder {
    private fun RandomAccessFile.u16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.u32(): Long = (u16().toLong() or (u16().toLong() shl 16))
    private fun RandomAccessFile.tag(): String = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)
    fun inspect(file: File): WavInfo = RandomAccessFile(file, "r").use { f ->
        require(f.length() >= 12 && f.tag() == "RIFF") { "RIFF WAVではありません" }
        val end = f.u32()+8
        require(end <= f.length() && end >= 12 && f.tag() == "WAVE") { "WAVヘッダーが壊れています" }
        var format = 0; var channels = 0; var rate = 0; var bits = 0; var align = 0
        var dataOffset = -1L; var dataSize = 0L
        while (f.filePointer+8 <= end) {
            val tag = f.tag(); val size = f.u32(); val start = f.filePointer
            require(size <= end-start) { "RIFFチャンクがファイル範囲外です" }
            when (tag) {
                "fmt " -> {
                    require(size >= 16 && format == 0) { "fmtチャンクが不正です" }
                    format = f.u16(); channels = f.u16(); rate = f.u32().toInt(); f.u32(); align = f.u16(); bits = f.u16()
                }
                "data" -> { require(dataOffset < 0) { "複数のdataチャンクには未対応です" }; dataOffset = start; dataSize = size }
            }
            f.seek(start+size+(size and 1))
        }
        require(format == 1 || format == 3) { "WAV形式 $format は未対応です（PCM / IEEE floatのみ。Extensibleは未対応）" }
        require(channels in 1..2 && rate in 8000..192000) { "WAVは1/2ch、8～192 kHzに対応します" }
        require((format == 1 && bits in listOf(16,24,32)) || (format == 3 && bits == 32)) { "PCM16/24/32、float32のみ対応します" }
        require(align == channels*bits/8 && dataOffset >= 0 && dataSize > 0 && dataSize%align == 0L) { "WAVデータ長またはフレーム幅が不正です" }
        WavInfo(format, channels, rate, bits, dataOffset, dataSize)
    }
    fun decode(bytes: ByteArray, offset: Int, bits: Int, float: Boolean): Double {
        var raw = 0
        for (i in 0 until bits/8) raw = raw or ((bytes[offset+i].toInt() and 255) shl (8*i))
        if (float) return Float.fromBits(raw).toDouble().also { require(it.isFinite()) { "WAVにNaN/Infinityがあります" } }
        val signed = when (bits) { 16 -> raw.toShort().toInt(); 24 -> (raw shl 8) shr 8; else -> raw }
        return signed.toDouble()/2.0.pow(bits-1)
    }
    /** Bounded decode cache and 96-tap Blackman sinc resampling, performed before playback. */
    fun convert(input: File, output: File, mono: MonoMode): Long {
        val info = inspect(input)
        require(info.frames.toDouble()/info.rate <= 7200) { "初期版のWAV上限は2時間です" }
        val frames = ceil(info.frames.toDouble()*SAMPLE_RATE/info.rate).toLong()
        RandomAccessFile(input, "r").use { file ->
            val cache = ByteArray(8192*info.frameBytes); var cacheStart = -1L; var cacheFrames = 0
            fun sample(frame: Long): Double {
                if (frame !in 0 until info.frames) return 0.0
                if (frame < cacheStart || frame >= cacheStart+cacheFrames) {
                    cacheStart = max(0, frame-128); cacheFrames = min(8192L, info.frames-cacheStart).toInt()
                    file.seek(info.dataOffset+cacheStart*info.frameBytes); file.readFully(cache, 0, cacheFrames*info.frameBytes)
                }
                val o = (frame-cacheStart).toInt()*info.frameBytes
                val l = decode(cache, o, info.bits, info.format == 3)
                if (info.channels == 1) return l
                val r = decode(cache, o+info.bits/8, info.bits, info.format == 3)
                return when (mono) { MonoMode.LEFT -> l; MonoMode.RIGHT -> r; MonoMode.AVERAGE -> (l+r)/2 }
            }
            BufferedOutputStream(FileOutputStream(output)).use { dest ->
                val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                val ratio = info.rate.toDouble()/SAMPLE_RATE
                val cutoff = min(1.0, 1.0/ratio)*0.94
                // Polyphase table makes long imports practical without unbounded memory use.
                val phases = 1024; val radius = 48
                val table = Array(phases) { p ->
                    val frac = p.toDouble()/phases
                    DoubleArray(radius*2) { k ->
                        val x = k-radius+1-frac
                        val t = cutoff*x
                        (if (abs(t) < 1e-12) cutoff else sin(PI*t)/(PI*x)) *
                            (0.42+0.5*cos(PI*x/radius)+0.08*cos(2*PI*x/radius))
                    }.also { h -> val sum = h.sum(); for (k in h.indices) h[k] /= sum }
                }
                for (i in 0 until frames) {
                    if (i%4096 == 0L && Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val x = i*ratio; val base = floor(x).toLong()
                    var value = 0.0
                    if (info.rate == SAMPLE_RATE) value = sample(i) else {
                        val h = table[((x-base)*phases).toInt().coerceIn(0, phases-1)]
                        for (k in h.indices) value += sample(base+k-radius+1)*h[k]
                    }
                    require(value.isFinite() && abs(value) <= Float.MAX_VALUE) { "WAVのサンプルが範囲外です" }
                    buffer.putFloat(value.toFloat())
                    if (!buffer.hasRemaining()) { dest.write(buffer.array()); buffer.clear() }
                }
                dest.write(buffer.array(), 0, buffer.position())
            }
        }
        return frames
    }
}

/** One producer, one audio consumer, preallocated bounded queues. No disk I/O in read(). */
class WavSource(private val file: File, private val loop: Boolean, private val fadeLoop: Boolean) : Closeable {
    private class Chunk { val values = DoubleArray(BLOCK); var generation = 0L; var end = false; var frame = 0L }
    private val free = ArrayBlockingQueue<Chunk>(32)
    private val ready = ArrayBlockingQueue<Chunk>(32)
    private val seekVersion = AtomicLong(0)
    @Volatile private var seekFrame = 0L
    @Volatile private var running = true
    @Volatile var finished = false; private set
    @Volatile var positionFrames = 0L; private set
    @Volatile var underruns = 0L; private set
    @Volatile var error: String? = null; private set
    val totalFrames = file.length()/4
    private val thread: Thread
    init {
        require(totalFrames > 0)
        repeat(32) { free.add(Chunk()) }
        thread = Thread({ produce() }, "WAV-prefetch").apply { isDaemon = true; start() }
    }
    fun seek(fraction: Double) { seekFrame = (fraction.coerceIn(0.0, 1.0)*(totalFrames-1)).toLong(); seekVersion.incrementAndGet(); finished = false }
    fun read(out: DoubleArray) {
        val generation = seekVersion.get()
        var c = ready.poll()
        while (c != null && c.generation != generation) { free.offer(c); c = ready.poll() }
        if (c == null) { out.fill(0.0); if (!finished) underruns++; return }
        System.arraycopy(c.values, 0, out, 0, BLOCK)
        positionFrames = c.frame; finished = c.end
        free.offer(c)
    }
    private fun produce() {
        try {
            RandomAccessFile(file, "r").use { f ->
                val bytes = ByteArray(16384); val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var start = -1L; var count = 0
                fun read(frame: Long): Double {
                    if (frame !in 0 until totalFrames) return 0.0
                    if (frame < start || frame >= start+count) {
                        start = frame; count = min(4096L, totalFrames-frame).toInt()
                        f.seek(frame*4); f.readFully(bytes, 0, count*4)
                    }
                    return bb.getFloat(((frame-start)*4).toInt()).toDouble()
                }
                val fade = if (fadeLoop && loop) min(240L, totalFrames/2).toInt() else 0
                val head = DoubleArray(fade) { read(it.toLong()) }
                var pos = 0L; var generation = seekVersion.get()
                while (running) {
                    val c = free.take()
                    val v = seekVersion.get()
                    if (v != generation) { generation = v; pos = seekFrame }
                    c.generation = generation
                    for (i in 0 until BLOCK) {
                        if (pos >= totalFrames && loop) pos = fade.toLong()
                        var x = read(pos)
                        if (fade > 0 && pos >= totalFrames-fade) {
                            val index = (pos-(totalFrames-fade)).toInt().coerceIn(0, fade-1)
                            val t = (index+1).toDouble()/fade
                            x = x*(1-t)+head[index]*t
                        }
                        c.values[i] = x
                        pos++
                    }
                    c.frame = min(pos, totalFrames); c.end = !loop && pos >= totalFrames
                    ready.put(c)
                }
            }
        } catch (_: InterruptedException) { /* close */ }
        catch (e: Exception) { error = e.message ?: "WAV読込エラー"; finished = true }
    }
    override fun close() { running = false; thread.interrupt() }
}
