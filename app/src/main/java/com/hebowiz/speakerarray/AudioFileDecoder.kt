package com.hebowiz.speakerarray

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Build
import com.hebowiz.speakerarray.core.MonoMode
import com.hebowiz.speakerarray.core.WavDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** File import only: decode incrementally on a worker, then reuse the existing mono/sinc converter. */
object AudioFileDecoder {
    const val MAX_INPUT_BYTES=1_073_741_824L
    const val MAX_DECODED_BYTES=1_073_741_824L
    private const val MAX_SECONDS=7200

    fun convert(input: File, output: File, mono: MonoMode, checkCancelled: ()->Unit = {}): Long {
        require(input.length() in 1..MAX_INPUT_BYTES) {"音声ファイルの読込上限は1 GiBです（空ファイルは読込不可）"}
        checkCancelled()
        val wav=RandomAccessFile(input,"r").use {f->
            val header=ByteArray(12)
            f.length()>=12 && f.read(header)==12 && String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WAVE"
        }
        // Preserve PCM24/32 and float WAV support independently of the device's codecs.
        if(wav) return WavDecoder.convert(input,output,mono,checkCancelled)
        val decoded=File.createTempFile("audio-decoded-",".wav",output.parentFile)
        try {
            decode(input,decoded,checkCancelled)
            return WavDecoder.convert(decoded,output,mono,checkCancelled)
        } finally {decoded.delete()}
    }

    private data class Pcm(val rate: Int,val channels: Int,val bits: Int,val float: Boolean) {
        val frameBytes get()=channels*bits/8
    }
    private fun pcm(format: MediaFormat): Pcm {
        val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(rate in 8000..192000 && channels in 1..2) {"音声ファイルは1/2ch、8～192 kHzに対応します"}
        val encoding=if(format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        return when(encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> Pcm(rate,channels,16,false)
            AudioFormat.ENCODING_PCM_FLOAT -> Pcm(rate,channels,32,true)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> Pcm(rate,channels,24,false)
            AudioFormat.ENCODING_PCM_32BIT -> Pcm(rate,channels,32,false)
            else -> error("デコーダーのPCM形式 $encoding には対応していません")
        }
    }
    private fun decode(input: File, output: File, checkCancelled: ()->Unit) {
        val extractor=MediaExtractor()
        var codec: MediaCodec?=null
        var started=false
        try {
            extractor.setDataSource(input.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
                ?: error("対応する音声トラックがありません")
            val format=extractor.getTrackFormat(track)
            val mime=requireNotNull(format.getString(MediaFormat.KEY_MIME))
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2) {"音声ファイルはモノラル／ステレオに対応します"}
            if(format.containsKey(MediaFormat.KEY_DURATION)) require(format.getLong(MediaFormat.KEY_DURATION)<=MAX_SECONDS*1_000_000L) {"音声ファイルの時間上限は2時間です"}
            extractor.selectTrack(track)
            // Old FLAC extractors already return PCM. Never relabel that PCM as float.
            // API 27 OMX MP3 also crashes if forced to float; retain its native PCM format.
            fun configure(encoding: Int?=null): MediaCodec {
                if(encoding!=null) format.setInteger(MediaFormat.KEY_PCM_ENCODING,encoding)
                val decoder=MediaCodec.createDecoderByType(mime)
                try {decoder.configure(format,null,null,0);return decoder}
                catch(e: Exception) {decoder.release();throw e}
            }
            val decoder=if(mime==MediaFormat.MIMETYPE_AUDIO_FLAC && Build.VERSION.SDK_INT>=29) {
                try {configure(AudioFormat.ENCODING_PCM_FLOAT)} catch(e: Exception) {
                    checkCancelled();configure(AudioFormat.ENCODING_PCM_16BIT)
                }
            } else configure()
            codec=decoder;decoder.start();started=true
            val info=MediaCodec.BufferInfo()
            var inputEnded=false;var outputEnded=false;var written=0L;var layout: Pcm?=null
            var lastProgress=SystemClock.elapsedRealtime()
            RandomAccessFile(output,"rw").use {dest->
                dest.setLength(0);dest.write(ByteArray(44))
                val bytes=ByteArray(65536)
                while(!outputEnded) {
                    checkCancelled()
                    require(SystemClock.elapsedRealtime()-lastProgress<15_000) {"音声デコーダーの応答が停止しました"}
                    if(!inputEnded) {
                        val index=decoder.dequeueInputBuffer(10_000)
                        if(index>=0) {
                            val buffer=requireNotNull(decoder.getInputBuffer(index));buffer.clear()
                            val size=extractor.readSampleData(buffer,0)
                            if(size<0) {decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                            else {
                                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0) {"暗号化された音声は読み込めません"}
                                decoder.queueInputBuffer(index,0,size,extractor.sampleTime.coerceAtLeast(0),0)
                                extractor.advance()
                            }
                            lastProgress=SystemClock.elapsedRealtime()
                        }
                    }
                    when(val index=decoder.dequeueOutputBuffer(info,10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val next=pcm(decoder.outputFormat)
                            require(written==0L || layout==next) {"途中で音声形式が変わるファイルには対応していません"}
                            layout=next;lastProgress=SystemClock.elapsedRealtime()
                        }
                        else -> if(index>=0) {
                            try {
                                if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                                    val current=layout ?: pcm(decoder.outputFormat).also {layout=it}
                                    require(info.size%current.frameBytes==0) {"デコードした音声のフレーム幅が不正です"}
                                    require(written+info.size<=MAX_DECODED_BYTES) {"展開後の音声データが1 GiBを超えます"}
                                    require((written+info.size).toDouble()/current.frameBytes/current.rate<=MAX_SECONDS) {"音声ファイルの時間上限は2時間です"}
                                    val buffer=requireNotNull(decoder.getOutputBuffer(index))
                                    buffer.position(info.offset);buffer.limit(info.offset+info.size)
                                    while(buffer.hasRemaining()) {
                                        checkCancelled()
                                        val n=minOf(bytes.size,buffer.remaining());buffer.get(bytes,0,n);dest.write(bytes,0,n)
                                    }
                                    written+=info.size
                                }
                                outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                                lastProgress=SystemClock.elapsedRealtime()
                            } finally {decoder.releaseOutputBuffer(index,false)}
                        }
                    }
                }
                require(written>0) {"音声データをデコードできませんでした"}
                val p=requireNotNull(layout)
                val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36+written).toInt()).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                    .putInt(16).putShort(if(p.float) 3 else 1).putShort(p.channels.toShort()).putInt(p.rate)
                    .putInt(p.rate*p.frameBytes).putShort(p.frameBytes.toShort()).putShort(p.bits.toShort())
                    .put("data".toByteArray(Charsets.US_ASCII)).putInt(written.toInt())
                dest.seek(0);dest.write(header.array())
            }
        } finally {
            try {if(started) runCatching {codec?.stop()};codec?.release()} finally {extractor.release()}
        }
    }
}
