package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

class DspTest {
    @Test fun fftRoundTrip() {
        val r=DoubleArray(2048) {sin(it*0.17)+0.2*cos(it*0.023)}; val original=r.copyOf();val i=DoubleArray(r.size)
        Fft.transform(r,i);Fft.transform(r,i,true)
        assertArrayEquals(original,r,1e-10)
    }
    @Test fun flatAndBypassHaveIdenticalDelay() {
        for(n in listOf(257,513,1025)) {
            val h=FirDesigner.design(FirSpec(),n)
            assertEquals(1.0,h[n/2],0.0);assertEquals(1.0,h.sum(),0.0)
            val p=Processing(length=n,right=Channel(enabled=false))
            val l=ResponseAnalyzer.channel(p,false,1143.0,false);val r=ResponseAnalyzer.channel(p,true,1143.0,false)
            assertEquals(l.re,r.re,1e-12);assertEquals(l.im,r.im,1e-12)
        }
    }
    @Test fun longFirPhaseUnwrapDoesNotAliasOnLogGrid() {
        val curve=ResponseAnalyzer.curve(FirDesigner.flat(1025))
        for(i in curve.hz.indices) assertEquals(-360*curve.hz[i]*512/SAMPLE_RATE,curve.phase[i],1e-7)
    }
    @Test fun arbitraryImpulseIsNotCenteredOrNormalized() {
        val f=FirSpec(FirMode.COEFFICIENTS,coefficients=listOf(2.0,-0.5,0.25))
        val h=FirDesigner.design(f,513)
        assertEquals(2.0,h[0],0.0);assertEquals(-0.5,h[1],0.0);assertEquals(0.0,h[256],0.0)
        val z=ResponseAnalyzer.response(h,0.0);assertEquals(1.75,z.re,1e-12)
    }
    @Test fun firDesignerMatchesIndependentLowpassResponse() {
        val h=FirDesigner.design(FirSpec(FirMode.LPF,high=4000.0),513)
        assertEquals(1.0,independentMagnitude(h,1000.0),1e-4)
        assertTrue(independentMagnitude(h,8000.0)<1e-4)
        assertEquals(0.5,independentMagnitude(h,4000.0),0.002)
        for(i in h.indices) assertEquals(h[i],h[h.lastIndex-i],1e-12)
    }
    @Test fun eqHasActualRequestedGainAndSymmetry() {
        val h=FirDesigner.design(FirSpec(FirMode.EQ,eq=List(10) {6.0}),513)
        assertEquals(dbGain(6.0),independentMagnitude(h,1143.0),1e-9)
        for(i in h.indices) assertEquals(h[i],h[h.lastIndex-i],1e-12)
        assertEquals(6.0,FirDesigner.targetDb(FirSpec(FirMode.EQ,eq=List(10) {6.0}),24000.0),0.0)
    }
    @Test fun fractionalDelayErrorAndIntegerBoundaries() {
        var maxDb=0.0;var maxPhase=0.0;var wideDb=0.0;var widePhase=0.0
        for(delay in listOf(0.0,0.001,0.048,0.25,0.5,0.95,0.999,1.0,1.001,21.0,479.9,480.0)) {
            val h=FractionalDelay.kernel(delay)
            for(hz in 100..20000 step 100) {
                val z=ResponseAnalyzer.response(h,hz.toDouble())
                val error=abs(wrapPhase((z.phase+2*PI*hz*(16+delay)/SAMPLE_RATE)*180/PI))
                val db=abs(levelDb(z.magnitude))
                if(hz<=8000) {maxDb=max(maxDb,db);maxPhase=max(maxPhase,error)}
                wideDb=max(wideDb,db);widePhase=max(widePhase,error)
            }
        }
        println("fractional delay 100-8000 Hz: max $maxDb dB, $maxPhase deg; 100-20000 Hz: $wideDb dB, $widePhase deg")
        assertTrue(maxDb<0.2);assertTrue(maxPhase<1.0)
    }
    @Test fun relativeDelaySignAndZero() {
        for(us in listOf(-10000.0,-437.3,-20.833333,-1.0,0.0,1.0,20.833333,437.3,10000.0)) {
            val p=Processing(delayUs=us)
            val l=ResponseAnalyzer.channel(p,false,1000.0,false);val r=ResponseAnalyzer.channel(p,true,1000.0,false)
            assertEquals(0.0,wrapPhase((r.phase-l.phase)*180/PI+360*1000*us/1e6),0.02)
        }
    }
    @Test fun theoryFixedReferenceAndPolarity() {
        assertEquals(0.0,ResponseAnalyzer.pattern(Session())[90],1e-8)
        val anti=Session(processing=Processing(phaseDeg=180.0))
        assertEquals(-60.0,ResponseAnalyzer.pattern(anti)[90],1e-8)
        val one=Session(processing=Processing(right=Channel(muted=true)))
        assertEquals(-6.0206,ResponseAnalyzer.pattern(one)[90],0.0001)
        val coincident=ResponseAnalyzer.pattern(Session(spacingMm=0.0))
        assertTrue(coincident.all {abs(it)<1e-8})
        val boosted=Session(processing=Processing(left=Channel(gainDb=6.0),right=Channel(gainDb=6.0)))
        assertEquals(6.0,ResponseAnalyzer.pattern(boosted)[90],1e-8)
        assertArrayEquals(ResponseAnalyzer.pattern(boosted),ResponseAnalyzer.pattern(boosted.copy(masterDb=-60.0)),0.0)
    }
    @Test fun rightDelaySteersTowardPositiveAngle() {
        val delay=0.15*sin(30*PI/180)/343*1e6
        val s=Session(processing=Processing(delayUs=delay),source=SourceSettings(kind=SourceKind.WHITE),evaluationHz=1000.0)
        val p=ResponseAnalyzer.pattern(s)
        val index=p.indices.maxBy {p[it]}
        assertEquals(30,index-90)
        assertArrayEquals(p,ResponseAnalyzer.pattern(s.copy(processing=s.processing.copy(phaseDeg=180.0))),0.0)
    }
    @Test fun asymmetricFirGroupDelayAndTheory() {
        val h=FirDesigner.design(FirSpec(FirMode.COEFFICIENTS,coefficients=listOf(0.0,1.0)),513)
        val curve=ResponseAnalyzer.curve(h)
        curve.groupMs.forEach {assertEquals(1000.0/SAMPLE_RATE,it,1e-9)}
        val s=Session(processing=Processing(right=Channel(fir=FirSpec(FirMode.COEFFICIENTS,coefficients=listOf(1.0)))))
        assertTrue(ResponseAnalyzer.pattern(s)[90]<-0.1)
        val zero=ResponseAnalyzer.curve(DoubleArray(513))
        assertTrue(zero.phase.all {it.isNaN()});assertTrue(zero.groupMs.all {it.isNaN()})
    }
    @Test fun commonSourcesProduceIdenticalChannels() {
        for(kind in listOf(SourceKind.SINE,SourceKind.WHITE,SourceKind.PINK,SourceKind.SWEEP)) {
            val source=SourceEngine(SourceSettings(kind=kind));val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK)
            repeat(40) {source.render(l,r,1143.0,0.0);assertArrayEquals(l,r,0.0)}
        }
    }
    @Test fun oscillatorFrequencyAndRelativePhase() {
        val source=SourceEngine(SourceSettings(frequency=1000.0));val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK)
        repeat(50) {source.render(l,r,1000.0,90.0)}
        var energy=0.0;var orthogonal=0.0
        repeat(30) {source.render(l,r,1000.0,90.0); for(i in l.indices) {energy+=l[i]*l[i];orthogonal+=l[i]*r[i]}}
        assertEquals(0.5,energy/(30*BLOCK),0.001);assertEquals(0.0,orthogonal/(30*BLOCK),0.001)
    }
    @Test fun convolutionImpulseMatchesFirAcrossBlockBoundaries() {
        val coeff=List(1025) {if(it%101==0) 0.1 else 0.0}
        val p=Processing(length=1025,left=Channel(fir=FirSpec(FirMode.COEFFICIENTS,coefficients=coeff)),right=Channel(fir=FirSpec(FirMode.COEFFICIENTS,coefficients=coeff)))
        val engine=DspEngine(PreparedProcessing.create(p));val l=DoubleArray(BLOCK);val r=DoubleArray(BLOCK);val out=FloatArray(2*BLOCK)
        repeat(30) {engine.process(l,r,out,0.0,false)}
        val result=DoubleArray(6*BLOCK)
        repeat(6) {b ->l.fill(0.0);r.fill(0.0);if(b==0) {l[0]=1.0;r[0]=1.0};engine.process(l,r,out,0.0,false);repeat(BLOCK) {i->result[b*BLOCK+i]=out[2*i].toDouble()}}
        for(i in result.indices) assertEquals(coeff.getOrElse(i-16) {0.0},result[i],1e-7)
    }
    @Test fun gainPolarityMuteAndPhysicalSwap() {
        val left=Channel(gainDb=-6.0);val right=Channel(inverted=true)
        val p=Processing(left=left,right=right)
        val engine=DspEngine(PreparedProcessing.create(p));val l=DoubleArray(BLOCK) {0.1};val r=l.copyOf();val out=FloatArray(2*BLOCK)
        repeat(30) {engine.process(l,r,out,0.0,false)}
        assertEquals(0.1*dbGain(-6.0),out[0].toDouble(),1e-7);assertEquals(-0.1,out[1].toDouble(),1e-7)
        engine.process(l,r,out,0.0,true);assertEquals(-0.1,out[0].toDouble(),1e-7)
        engine.submit(PreparedProcessing.create(p.copy(left=left.copy(muted=true)),2))
        repeat(8) {engine.process(l,r,out,0.0,false)}
        assertEquals(0.0,out[0].toDouble(),1e-7);assertEquals(-0.1,out[1].toDouble(),1e-7)
    }
    @Test fun filterUpdatesUseHistoryAndLatestQueuedGeneration() {
        val flat=FirSpec(FirMode.COEFFICIENTS,coefficients=listOf(1.0))
        val p=Processing(left=Channel(fir=flat),right=Channel(fir=flat))
        val engine=DspEngine(PreparedProcessing.create(p));val l=DoubleArray(BLOCK) {0.1};val r=l.copyOf();val out=FloatArray(2*BLOCK)
        repeat(30) {engine.process(l,r,out,0.0,false)}
        engine.submit(PreparedProcessing.create(p.copy(left=p.left.copy(fir=FirSpec())),1))
        engine.process(l,r,out,0.0,false)
        assertTrue(out.all {abs(it-0.1f)<1e-6})
        engine.submit(PreparedProcessing.create(p.copy(left=p.left.copy(gainDb=-6.0)),2))
        engine.submit(PreparedProcessing.create(p.copy(left=p.left.copy(gainDb=-12.0)),3))
        repeat(20) {engine.process(l,r,out,0.0,false);assertTrue(out.all {it.isFinite() && abs(it)<=0.10001f})}
        assertEquals(3L,engine.appliedGeneration);assertEquals(0.1*dbGain(-12.0),out[0].toDouble(),1e-7)
    }
    @Test fun clippingIsReportedBeforeSaturation() {
        val p=Processing(left=Channel(gainDb=12.0),right=Channel(gainDb=12.0))
        val engine=DspEngine(PreparedProcessing.create(p));val input=DoubleArray(BLOCK) {1.0};val out=FloatArray(BLOCK*2)
        repeat(30) {engine.process(input,input,out,0.0,false)}
        assertTrue(engine.peakLeft>1.0);assertTrue(engine.clips>0);assertTrue(out.all {it==1f})
        engine.process(input,input,out,0.0,false,DoubleArray(BLOCK));assertTrue(out.all {it==0f})
    }
    @Test fun shortWritesPreserveInterleavedOrder() {
        val input=FloatArray(512) {it.toFloat()};val result=mutableListOf<Float>()
        StereoWriter.writeAll(input) {a,o,n ->val count=min(n,7);repeat(count) {result.add(a[o+it])};count}
        assertArrayEquals(input,result.toFloatArray(),0f)
        assertThrows(IllegalStateException::class.java) {StereoWriter.writeAll(input) {_,_,_->-6}}
        assertThrows(IllegalStateException::class.java) {StereoWriter.writeAll(input) {_,_,_->0}}
    }
    @Test fun invalidFirRejected() {
        listOf(emptyList(),listOf(Double.NaN),listOf(Double.POSITIVE_INFINITY),List(514) {1.0}).forEach {c ->
            assertThrows(IllegalArgumentException::class.java) {FirDesigner.design(FirSpec(FirMode.COEFFICIENTS,coefficients=c),513)}
        }
        assertThrows(IllegalArgumentException::class.java) {FirDesigner.design(FirSpec(FirMode.BPF,low=8000.0,high=300.0),513)}
    }
    @Test fun benchmark1025TapCrossfade() {
        val p=Processing(length=1025)
        val engine=DspEngine(PreparedProcessing.create(p));val input=DoubleArray(BLOCK) {sin(it.toDouble())*0.1};val output=FloatArray(BLOCK*2)
        repeat(100) {engine.process(input,input,output,-24.0,false)}
        val a=PreparedProcessing.create(p.copy(delayUs=10000.0),1);val b=PreparedProcessing.create(p.copy(delayUs=-10000.0),2)
        val begin=System.nanoTime()
        repeat(500) {i ->if(i%7==0) engine.submit(if(i%2==0) a else b);engine.process(input,input,output,-24.0,false)}
        val elapsed=(System.nanoTime()-begin)/1e6
        println("Host JVM benchmark: 1025 taps 2ch crossfades, 500 blocks = $elapsed ms for ${500.0*BLOCK/SAMPLE_RATE*1000} ms audio (not Android performance guarantee)")
        assertTrue(output.all {it.isFinite()})
    }
    private fun independentMagnitude(h: DoubleArray,f: Double): Double {
        var a=0.0;var b=0.0
        for(n in h.indices) {val angle=2*PI*f*n/SAMPLE_RATE;a+=h[n]*cos(angle);b+=h[n]*sin(angle)}
        return sqrt(a*a+b*b)
    }
}
