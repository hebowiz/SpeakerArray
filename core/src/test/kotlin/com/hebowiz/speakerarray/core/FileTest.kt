package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import kotlin.math.*

class FileTest {
    @get:Rule val temp=TemporaryFolder()
    private fun wav(bits: Int=16,channels: Int=1,rate: Int=48000,format: Int=1,values: DoubleArray=doubleArrayOf(-1.0,0.0,0.5),extra: Boolean=true): File {
        val size=values.size*bits/8;val extraBytes=if(extra) 12 else 0
        val b=ByteBuffer.allocate(44+extraBytes+size+(size and 1)).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(t: String) {b.put(t.toByteArray(Charsets.US_ASCII))}
        tag("RIFF");b.putInt(b.capacity()-8);tag("WAVE")
        if(extra) {tag("JUNK");b.putInt(3);b.put(byteArrayOf(1,2,3,0))}
        tag("fmt ");b.putInt(16);b.putShort(format.toShort());b.putShort(channels.toShort());b.putInt(rate)
        b.putInt(rate*channels*bits/8);b.putShort((channels*bits/8).toShort());b.putShort(bits.toShort())
        tag("data");b.putInt(size)
        values.forEach {v ->
            if(format==3) b.putFloat(v.toFloat()) else {
                val raw=(v*2.0.pow(bits-1)).toLong().coerceIn(-(1L shl(bits-1)),(1L shl(bits-1))-1)
                repeat(bits/8) {i->b.put((raw shr(i*8)).toByte())}
            }
        }
        val file=temp.newFile();file.writeBytes(b.array());return file
    }
    private fun convert(file: File,mode: MonoMode=MonoMode.AVERAGE): FloatArray {
        val dest=temp.newFile();WavDecoder.convert(file,dest,mode)
        val b=ByteBuffer.wrap(dest.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining()/4) {b.float}
    }
    @Test fun allSupportedFormatsAndAdditionalOddRiffChunk() {
        for(bits in listOf(16,24,32)) {
            val f=wav(bits=bits);val info=WavDecoder.inspect(f)
            assertEquals(bits,info.bits);assertEquals(56L,info.dataOffset)
            assertArrayEquals(floatArrayOf(-1f,0f,0.5f),convert(f),1e-6f)
        }
        assertArrayEquals(floatArrayOf(-1.2f,0f,0.5f),convert(wav(bits=32,format=3,values=doubleArrayOf(-1.2,0.0,0.5))),1e-6f)
    }
    @Test fun stereoDownmixAndSelection() {
        val f=wav(channels=2,values=doubleArrayOf(0.5,-0.5,0.25,0.75))
        assertArrayEquals(floatArrayOf(0f,0.5f),convert(f),1e-6f)
        assertArrayEquals(floatArrayOf(0.5f,0.25f),convert(f,MonoMode.LEFT),1e-6f)
        assertArrayEquals(floatArrayOf(-0.5f,0.75f),convert(f,MonoMode.RIGHT),1e-6f)
    }
    @Test fun resampling44100To48000PreservesTone() {
        val src=DoubleArray(4410) {0.5*sin(2*PI*1000*it/44100)}
        val result=convert(wav(rate=44100,format=3,bits=32,values=src))
        assertEquals(4800,result.size)
        var error=0.0
        for(i in 100 until result.size-100) error=max(error,abs(result[i]-0.5*sin(2*PI*1000*i/48000)))
        println("44.1 -> 48 kHz tone peak error: $error")
        assertTrue(error<0.001)
    }
    @Test fun downsamplingSuppressesAliasing() {
        val src=DoubleArray(19200) {0.5*sin(2*PI*40000*it/192000)}
        val out=convert(wav(rate=192000,format=3,bits=32,values=src))
        val rms=sqrt(out.drop(100).dropLast(100).map {it.toDouble()*it}.average())
        println("192 -> 48 kHz 40kHz alias rms: $rms")
        assertTrue(rms<0.001)
    }
    @Test fun malformedAndUnsupportedWavRejected() {
        assertThrows(IllegalArgumentException::class.java) {WavDecoder.inspect(wav(format=65534))}
        val f=wav();f.writeBytes(f.readBytes().dropLast(2).toByteArray())
        assertThrows(IllegalArgumentException::class.java) {WavDecoder.inspect(f)}
        assertThrows(IllegalArgumentException::class.java) {convert(wav(bits=32,format=3,values=doubleArrayOf(Double.NaN)))}
    }
    @Test fun boundedReaderSeeksAndLoopsShortFiles() {
        val f=temp.newFile();val b=ByteBuffer.allocate(64*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(64) {b.putFloat(it.toFloat()/64)};f.writeBytes(b.array())
        WavSource(f,true,false).use {reader ->
            val out=DoubleArray(BLOCK)
            val deadline=System.nanoTime()+2_000_000_000
            do {reader.read(out);if(out.any {it!=0.0}) break;Thread.sleep(2)} while(System.nanoTime()<deadline)
            assertEquals(1.0/64,out[1],1e-8);assertEquals(out[1],out[65],0.0)
            reader.seek(0.5)
            do {reader.read(out);if(out[0]>0.0) break;Thread.sleep(2)} while(System.nanoTime()<deadline)
            assertEquals(31.0/64,out[0],1e-8)
        }
        WavSource(f,true,true).use {reader ->
            val out=DoubleArray(BLOCK);Thread.sleep(20);reader.read(out)
            assertTrue(out.all {it.isFinite() && it in 0.0..1.0})
        }
    }
    @Test fun sessionAndFirRoundTripWithoutNormalization() {
        val spec=FirSpec(FirMode.COEFFICIENTS,coefficients=listOf(2.0,-1.0,0.3))
        val p=Processing(left=Channel(fir=spec),delayUs=-12.0,phaseDeg=90.0)
        val s=Session(processing=p,swapped=true,reverseLandscape=true,source=SourceSettings(kind=SourceKind.PINK),a=p,b=p.copy(delayUs=2.0))
        assertEquals(s,SessionRepository.decode(SessionRepository.encode(s)))
        val f=SessionRepository.importFir(SessionRepository.exportFir(spec,513),513,null)
        assertEquals(2.0,f.coefficients[0],0.0);assertEquals(-1.0,f.coefficients[1],0.0)
        assertEquals(513,f.coefficients.size)
    }
    @Test fun unknownVersionSampleRateAndWrongTypeRejected() {
        val text=SessionRepository.encode(Session())
        listOf(org.json.JSONObject(text).put("schemaVersion",999).toString(),text.replace("48000","44100"),text.replace("speakerarray-session","speakerarray-fir")).forEach {raw ->
            assertThrows(IllegalArgumentException::class.java) {SessionRepository.decode(raw)}
        }
    }
    @Test fun csvAndPasteContract() {
        val f=SessionRepository.importFir("coefficient\n1\n-0.2\n",513,48000)
        assertEquals(listOf(1.0,-0.2),f.coefficients)
        assertThrows(IllegalArgumentException::class.java) {SessionRepository.importFir("1\n2",513,null)}
        assertThrows(IllegalArgumentException::class.java) {SessionRepository.importFir("1,2\n3,4",513,48000)}
        assertEquals(listOf(1.0,2.0,-3.0),SessionRepository.pasteCoefficients("[1, 2\n-3]",513).coefficients)
        assertThrows(IllegalArgumentException::class.java) {SessionRepository.pasteCoefficients("NaN",513)}
    }
}
