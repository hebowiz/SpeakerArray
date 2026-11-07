package com.hebowiz.speakerarray

import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Offline decode only. These tests never open AudioTrack or AudioRecord. */
class AudioFileDecoderTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String, dir: File): File = File(dir,name).also {file->
        instrumentation.context.assets.open("audio/$name").use {src->file.outputStream().use {src.copyTo(it)}}
    }
    private fun samples(input: File, dir: File, mode: MonoMode): FloatArray {
        val out=File(dir,"converted.pcm")
        val count=AudioFileDecoder.convert(input,out,mode)
        val buffer=ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(count*4,out.length())
        return FloatArray(count.toInt()) {buffer.float}
    }
    private fun temp(test: (File)->Unit) {
        val dir=File(instrumentation.targetContext.cacheDir,"decoder-test-${System.nanoTime()}").apply {mkdirs()}
        try {test(dir)} finally {dir.deleteRecursively()}
    }
    private fun level(a: FloatArray,hz: Double): Double {
        var real=0.0;var imag=0.0
        for(i in 12000 until 36000) {val phase=2*PI*hz*i/SAMPLE_RATE;real+=a[i]*cos(phase);imag+=a[i]*sin(phase)}
        return hypot(real,imag)*2/24000
    }
    @Test fun commonFormatsDecodeResampleAndPreserveChannelSelection()=temp {dir->
        for(name in listOf("tone.wav","tone.mp3","tone.flac","tone.m4a","tone.aac","tone.ogg","tone.opus")) {
            val file=fixture(name,dir)
            val left=samples(file,dir,MonoMode.LEFT);val right=samples(file,dir,MonoMode.RIGHT);val average=samples(file,dir,MonoMode.AVERAGE)
            assertTrue("$name frames=${left.size}",left.size in 47000..55000)
            assertEquals(left.size,right.size);assertEquals(left.size,average.size)
            assertTrue(name,left.all {it.isFinite()} && right.all {it.isFinite()})
            assertEquals("$name left",.2,level(left,440.0),.015)
            assertEquals("$name right",.1,level(right,880.0),.015)
            assertTrue("$name crosstalk",level(left,880.0)<.01 && level(right,440.0)<.01)
            for(i in average.indices) assertEquals("$name mix $i",(left[i]+right[i])*.5,average[i].toDouble(),1e-6)
            assertTrue(dir.listFiles()!!.none {it.name.startsWith("audio-decoded-")})
        }
    }
    @Test fun flac24PreservesAmplitudeWithinDecoderPcmPrecision()=temp {dir->
        val reference=samples(fixture("reference-float.wav",dir),dir,MonoMode.LEFT)
        val lossless=samples(fixture("tone-24.flac",dir),dir,MonoMode.LEFT)
        assertEquals(reference.size,lossless.size)
        val maxError=reference.indices.maxOf {abs(reference[it]-lossless[it]).toDouble()}
        // Older Android FLAC extractors expose PCM16 before MediaCodec receives the data.
        println("24-bit FLAC maximum sample error: $maxError")
        assertTrue("24-bit FLAC max error=$maxError",maxError<4e-5)
    }
    @Test fun unsupportedAndCancelledImportsReleaseTemporaryDecodeFiles()=temp {dir->
        val output=File(dir,"output.pcm")
        for(file in listOf(File(dir,"bad.mp3").apply {writeText("not an audio file")},fixture("surround.flac",dir))) {
            assertThrows(Exception::class.java) {AudioFileDecoder.convert(file,output,MonoMode.AVERAGE)}
            assertTrue(dir.listFiles()!!.none {it.name.startsWith("audio-decoded-")})
        }
        val input=fixture("tone.mp3",dir);var checks=0
        assertThrows(CancellationException::class.java) {
            AudioFileDecoder.convert(input,output,MonoMode.LEFT) {if(++checks>=8) throw CancellationException("test")}
        }
        assertTrue(dir.listFiles()!!.none {it.name.startsWith("audio-decoded-")})
    }
}
