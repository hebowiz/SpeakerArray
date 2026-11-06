package com.hebowiz.speakerarray.core

import kotlin.math.*

data class EndpointKey(val type: Int, val name: String, val address: String = "")
enum class ProbeKind { SWEEP, MULTITONE }
data class AutoFirSettings(
    val output: EndpointKey? = null, val input: EndpointKey? = null,
    val preset: Int = 6, val inputRate: Int = 48000, val referenceRight: Boolean = false,
    val lowHz: Double = 300.0, val highHz: Double = 8000.0, val seconds: Double = 3.0,
    val tailSeconds: Double = 0.5, val latencySeconds: Double = 1.5,
    val taps: Int = 1025, val regularization: Double = 0.01, val maxBoostDb: Double = 6.0,
    val signal: ProbeKind = ProbeKind.SWEEP
) {
    fun validate() {
        require(inputRate in listOf(8000,16000,32000,44100,48000))
        require(preset in listOf(6,7,9)) { "録音プリセットが不正です" }
        finiteRange(lowHz,80.0,10000.0,"測定下限"); finiteRange(highHz,100.0,16000.0,"測定上限")
        require(highHz > lowHz && highHz < inputRate*0.45) { "測定帯域は入力PCMの0.45倍未満にしてください" }
        finiteRange(seconds,1.0,5.0,"スイープ秒"); finiteRange(tailSeconds,0.1,2.0,"残響秒")
        finiteRange(latencySeconds,0.2,3.0,"同期探索秒")
        require(taps in listOf(257,513,1025)); finiteRange(regularization,0.0001,1.0,"正則化")
        finiteRange(maxBoostDb,0.0,12.0,"最大ブースト")
        require(10*(seconds+2*tailSeconds+latencySeconds+0.55)+20<=MeasurementLimits.MAX_SECONDS) { "測定時間が上限を超えます" }
    }
}

object MeasurementLimits {
    const val SNR_DB = 20.0
    const val REPEAT_DB = 1.0
    const val REPEAT_DEGREES = 5.0
    const val MIN_COVERAGE = 0.8
    const val IMPROVEMENT_DB = 3.0
    const val MAX_WORSENING_DB = 3.0
    const val MAX_SECONDS = 240
    const val MAX_CLOCK_PPM = 300.0
    const val MAX_SYNC_RESIDUAL = 0.35 // 48 kHz samples, common reference pilots only
}

data class TimingFit(val offset: Double, val ratio: Double, val residual: Double,
                     val emitted: List<Double>, val captured: List<Double>) {
    val ppm get() = (ratio-1)*1e6
}

/** Coordinates passed here are both expressed in nominal 48 kHz frames. */
object MeasurementSync {
    fun estimate(emitted: List<Double>, captured: List<Double>): TimingFit {
        require(emitted.size >= 3 && emitted.size == captured.size)
        val x = emitted.average(); val y = captured.average()
        val ratio = emitted.indices.sumOf { (emitted[it]-x)*(captured[it]-y) } /
            emitted.sumOf { (it-x).pow(2) }
        val offset = y-ratio*x
        val residual = emitted.indices.maxOf { abs(captured[it]-offset-ratio*emitted[it]) }
        return TimingFit(offset,ratio,residual,emitted,captured)
    }
    fun validate(fit: TimingFit) {
        require(fit.ratio.isFinite() && abs(fit.ppm) <= MeasurementLimits.MAX_CLOCK_PPM) { "クロック差が上限を超えました: ${fit.ppm} ppm" }
        require(fit.residual <= MeasurementLimits.MAX_SYNC_RESIDUAL) { "同期残差 ${fit.residual} sample（${fit.residual*1000/SAMPLE_RATE} ms）。遅延変動・信号不足・同期検出誤差を確認してください" }
    }
    fun fit(emitted: List<Double>, captured: List<Double>)=estimate(emitted,captured).also {validate(it)}

    fun sample(pcm: DoubleArray, position: Double): Double {
        val base = floor(position).toInt()
        require(base >= 16 && base+16 < pcm.size) { "録音余裕が不足しています" }
        var sum=0.0; var norm=0.0
        for (i in base-15..base+16) {
            val d=position-i
            val w=if(abs(d)<1e-10) 1.0 else sin(PI*d)/(PI*d)*(0.5+0.5*cos(PI*d/16))
            sum+=pcm[i]*w; norm+=w
        }
        return sum/norm
    }

    /** FFT matched filter. The same physical reference speaker supplies every pilot. */
    fun locate(pcm: DoubleArray, pilot: DoubleArray, from: Int, until: Int): Double {
        val start=max(0,from); val end=min(pcm.size,until)
        require(end-start > pilot.size)
        val n=powerOfTwo(end-start+pilot.size)
        val a=DoubleArray(n) { if(it<end-start) pcm[start+it] else 0.0 }; val ai=DoubleArray(n)
        val b=pilot.copyOf(n); val bi=DoubleArray(n)
        Fft.transform(a,ai); Fft.transform(b,bi)
        for(i in a.indices) { val r=a[i]*b[i]+ai[i]*bi[i]; ai[i]=ai[i]*b[i]-a[i]*bi[i]; a[i]=r }
        Fft.transform(a,ai,true)
        val peak=(1 until end-start-pilot.size).maxBy { abs(a[it]) }
        val value=abs(a[peak]); val energy=pilot.sumOf {it*it}
        require(value/energy > 1e-6) { "パイロット無信号・同期失敗" }
        val normalized=value/sqrt(energy*(0 until pilot.size).sumOf {pcm[start+peak+it].pow(2)}.coerceAtLeast(1e-30))
        require(normalized > 0.12) { "パイロットを識別できません" }
        val l=abs(a[peak-1]); val r=abs(a[peak+1])
        val fraction=(0.5*(l-r)/(l-2*value+r)).coerceIn(-0.5,0.5)
        return start+peak+fraction
    }
}

fun powerOfTwo(size: Int): Int { var n=1; while(n<size) n=n shl 1; return n }

interface MeasurementSignal { fun generate(settings: AutoFirSettings): DoubleArray }
object KnownProbe : MeasurementSignal {
    override fun generate(settings: AutoFirSettings): DoubleArray {
        val count=(settings.seconds*SAMPLE_RATE).toInt(); val duration=count.toDouble()/SAMPLE_RATE
        val k=ln(settings.highHz/settings.lowHz)/duration
        return DoubleArray(count) { i ->
            val t=i.toDouble()/SAMPLE_RATE
            val fade=min(1.0,min(i,count-1-i)/480.0).coerceAtLeast(0.0)
            if(settings.signal==ProbeKind.SWEEP) sin(2*PI*settings.lowHz*expm1(k*t)/k)*fade
            else (0 until 64).sumOf { j ->
                val f=settings.lowHz*(settings.highHz/settings.lowHz).pow(j/63.0)
                sin(2*PI*f*t-PI*j*(j-1)/64)
            }/64*fade
        }
    }
    fun pilot(settings: AutoFirSettings) = generate(settings.copy(seconds=0.25,signal=ProbeKind.SWEEP))
}

data class Transfer(val re: DoubleArray, val im: DoubleArray, val snrDb: DoubleArray,
                    val recordedPower: DoubleArray = DoubleArray(re.size) {re[it]*re[it]+im[it]*im[it]}) {
    fun magnitude(k: Int)=hypot(re[k],im[k])
}
data class MeasuredPair(val left: Transfer, val right: Transfer, val weights: DoubleArray,
                        val coverage: Double, val repeatDb: Double, val repeatPhase: Double)

object TransferEstimator {
    const val SIZE=8192
    fun noiseSpectrum(pcm: DoubleArray): DoubleArray {
        require(pcm.size>=SIZE)
        val power=DoubleArray(SIZE/2+1);var count=0
        val window=DoubleArray(SIZE) {0.5-0.5*cos(2*PI*it/(SIZE-1))};val energy=window.sumOf {it*it}
        for(start in 0..pcm.size-SIZE step SIZE/2) {
            val re=DoubleArray(SIZE) {pcm[start+it]*window[it]};val im=DoubleArray(SIZE)
            Fft.transform(re,im)
            for(k in power.indices) power[k]+=(re[k]*re[k]+im[k]*im[k])/energy
            count++
        }
        return DoubleArray(power.size) {(power[it]/count).coerceAtLeast(1e-14)}
    }
    /** Input segment and written reference have the same common time origin, never per-channel peaks. */
    fun estimate(reference: DoubleArray, captured: DoubleArray, noisePower: Double,
                 noiseSpectrum: DoubleArray? = null): Transfer {
        val n=powerOfTwo(max(reference.size,captured.size))
        require(n>=SIZE && n<=1 shl 20)
        val x=reference.copyOf(n); val xi=DoubleArray(n); val y=captured.copyOf(n); val yi=DoubleArray(n)
        Fft.transform(x,xi); Fft.transform(y,yi)
        val re=DoubleArray(SIZE/2+1); val im=DoubleArray(re.size); val snr=DoubleArray(re.size);val recorded=DoubleArray(re.size)
        val floor=x.indices.maxOf {x[it]*x[it]+xi[it]*xi[it]}*1e-10
        for(k in re.indices) {
            val j=k*(n/SIZE); val power=x[j]*x[j]+xi[j]*xi[j]
            val divisor=max(power,floor).coerceAtLeast(1e-20)
            re[k]=(y[j]*x[j]+yi[j]*xi[j])/divisor
            im[k]=(yi[j]*x[j]-y[j]*xi[j])/divisor
            recorded[k]=(y[j]*y[j]+yi[j]*yi[j])/n.toDouble().pow(2)
            snr[k]=10*log10(((y[j]*y[j]+yi[j]*yi[j])/((noiseSpectrum?.get(k) ?: noisePower)*captured.size).coerceAtLeast(1e-24)).coerceAtLeast(1e-12))
            if(power<floor*100) snr[k]=-120.0
        }
        return Transfer(re,im,snr,recorded)
    }

    fun pair(left: List<Transfer>, right: List<Transfer>, settings: AutoFirSettings): MeasuredPair {
        require(left.size>=2 && right.size>=2)
        fun mean(values: List<Transfer>)=Transfer(DoubleArray(SIZE/2+1) {k->values.map {it.re[k]}.average()},
            DoubleArray(SIZE/2+1) {k->values.map {it.im[k]}.average()},DoubleArray(SIZE/2+1) {k->values.minOf {it.snrDb[k]}})
        val l=mean(left); val r=mean(right); val weights=DoubleArray(l.re.size)
        var count=0; var accepted=0; var worstDb=0.0; var worstPhase=0.0
        for(k in weights.indices) {
            val hz=k*SAMPLE_RATE.toDouble()/SIZE
            if(hz !in settings.lowHz..settings.highHz) continue
            count++
            var db=0.0; var phase=0.0
            for(group in listOf(left,right)) for(t in group.drop(1)) {
                db=max(db,abs(levelDb(t.magnitude(k))-levelDb(group[0].magnitude(k))))
                phase=max(phase,abs(wrapPhase((atan2(t.im[k],t.re[k])-atan2(group[0].im[k],group[0].re[k]))*180/PI)))
            }
            worstDb=max(worstDb,db); worstPhase=max(worstPhase,phase)
            if(min(l.snrDb[k],r.snrDb[k]) >= MeasurementLimits.SNR_DB && db<=MeasurementLimits.REPEAT_DB && phase<=MeasurementLimits.REPEAT_DEGREES) {
                accepted++
                val edge=min((hz-settings.lowHz)/(settings.lowHz*0.2),(settings.highHz-hz)/(settings.highHz*0.1)).coerceIn(0.0,1.0)
                weights[k]=0.5-0.5*cos(PI*edge)
            }
        }
        val coverage=accepted.toDouble()/max(1,count)
        require(coverage>=MeasurementLimits.MIN_COVERAGE) { "品質不足: 有効帯域 ${(coverage*100).toInt()}%、反復 $worstDb dB / $worstPhase °" }
        return MeasuredPair(l,r,weights,coverage,worstDb,worstPhase)
    }
}

data class FirSolution(val processing: Processing, val predictedDb: Double, val maxBoostDb: Double, val peakBound: Double)

/** Real causal finite-dimensional least squares, solved with a Toeplitz FFT normal operator. */
object AutoFirDesigner {
    fun design(pair: MeasuredPair, settings: AutoFirSettings, masterDb: Double, check: () -> Unit = {}): FirSolution {
        val n=TransferEstimator.SIZE; val taps=settings.taps; val delay=taps/2
        val ref=if(settings.referenceRight) pair.right else pair.left
        val correction=if(settings.referenceRight) pair.left else pair.right
        val scale=correction.re.indices.maxOf { correction.magnitude(it).pow(2)*pair.weights[it] }
        require(scale>1e-16) { "補正側が無信号です" }
        val normal=DoubleArray(n); val br=DoubleArray(n); val bi=DoubleArray(n)
        for(k in 0..n/2) {
            val w=pair.weights[k]; val phase=-2*PI*k*delay/n
            val tr=-(ref.re[k]*cos(phase)-ref.im[k]*sin(phase))
            val ti=-(ref.re[k]*sin(phase)+ref.im[k]*cos(phase))
            // Out-of-band penalty suppresses the correction; the reference remains nonzero.
            normal[k]=w*correction.magnitude(k).pow(2)/scale+settings.regularization+(1-w)*0.1
            br[k]=w*(correction.re[k]*tr+correction.im[k]*ti)/scale
            bi[k]=w*(correction.re[k]*ti-correction.im[k]*tr)/scale
            if(k in 1 until n/2) {normal[n-k]=normal[k];br[n-k]=br[k];bi[n-k]=-bi[k]}
        }
        bi[0]=0.0;bi[n/2]=0.0
        Fft.transform(br,bi,true)
        fun apply(v: DoubleArray): DoubleArray {
            val re=v.copyOf(n);val im=DoubleArray(n);Fft.transform(re,im)
            for(k in 0 until n) {re[k]*=normal[k];im[k]*=normal[k]}
            Fft.transform(re,im,true);return re.copyOf(taps)
        }
        val h=DoubleArray(taps); val residual=br.copyOf(taps);val direction=residual.copyOf()
        var rr=residual.sumOf {it*it}; val initial=rr
        for(iteration in 0 until 160) {
            check();if(rr<max(initial*1e-12,1e-24)) break
            val ad=apply(direction);val denominator=direction.indices.sumOf {direction[it]*ad[it]}
            require(denominator>0 && denominator.isFinite()) { "FIR設計が収束しません" }
            val alpha=rr/denominator
            for(i in h.indices) {h[i]+=alpha*direction[i];residual[i]-=alpha*ad[i]}
            val next=residual.sumOf {it*it}; val beta=next/rr
            for(i in direction.indices) direction[i]=residual[i]+beta*direction[i]
            rr=next
        }
        require(rr <= max(initial*1e-7,1e-20)) { "FIR設計の残差が収束しません" }
        val hr=h.copyOf(n);val hi=DoubleArray(n);Fft.transform(hr,hi)
        val boost=levelDb((0..n/2).maxOf {hypot(hr[it],hi[it])})
        require(boost <= settings.maxBoostDb) { "過大ブースト $boost dB（上限 ${settings.maxBoostDb} dB）" }
        val bound=max(1.0,h.sumOf {abs(it)})*dbGain(masterDb)
        require(bound<=0.9) { "出力余裕不足。共通出力を手動で下げて再測定してください" }
        var before=0.0;var after=0.0
        for(k in pair.weights.indices) {
            val phase=-2*PI*k*delay/n
            val a=Complex(ref.re[k],ref.im[k])*Complex.polar(phase)
            val b=Complex(correction.re[k],correction.im[k])*Complex(hr[k],hi[k])
            before+=pair.weights[k]*a.magnitude.pow(2);after+=pair.weights[k]*(a+b).magnitude.pow(2)
        }
        val fixed=DoubleArray(taps).also {it[delay]=1.0}
        fun channel(values: DoubleArray)=Channel(fir=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=values.toList()))
        val p=Processing(length=taps,left=channel(if(settings.referenceRight) h else fixed),right=channel(if(settings.referenceRight) fixed else h))
        return FirSolution(p,10*log10((after/before).coerceAtLeast(1e-12)),boost,bound)
    }
}

data class Verification(val reductionSingleDb: Double, val reductionBothDb: Double, val bandReduction: List<Double>, val noiseLimited: Boolean)
object MeasurementQuality {
    fun distinctChannels(pair: MeasuredPair) {
        val valid=pair.weights.indices.filter {pair.weights[it]>0}
        val different=valid.count {k ->
            abs(levelDb(pair.left.magnitude(k))-levelDb(pair.right.magnitude(k)))>1.0 ||
                abs(wrapPhase((atan2(pair.right.im[k],pair.right.re[k])-atan2(pair.left.im[k],pair.left.re[k]))*180/PI))>10.0
        }
        require(different>=valid.size*0.1) { "左右応答がほぼ同一で、独立した音響出力を確認できません。左右の確認とMIC位置を見直してください" }
    }
    fun input(pcm: DoubleArray) {
        require(pcm.isNotEmpty() && pcm.all {it.isFinite()}) { "PCM欠落" }
        require(pcm.none {abs(it)>=0.999}) { "MIC入力クリップ" }
        require(pcm.sumOf {it*it}/pcm.size>1e-12) { "MIC無信号・無音化" }
    }
    fun verify(single: Transfer, both: Transfer, corrected: Transfer, weights: DoubleArray): Verification {
        val valid=weights.indices.filter {weights[it]>0}
        require(valid.size>=20) { "検証帯域不足" }
        fun energy(t: Transfer, bins: List<Int>)=bins.sumOf {weights[it]*t.recordedPower[it]}.coerceAtLeast(1e-24)
        fun improvement(base: Transfer, bins: List<Int>)=10*log10(energy(base,bins)/energy(corrected,bins))
        require(valid.count {single.snrDb[it]>=20 && both.snrDb[it]>=20}>=valid.size*0.8) { "基準音がノイズ床付近・測定限界" }
        val a=improvement(single,valid);val b=improvement(both,valid)
        val bands=valid.groupBy { floor(3*log2(max(1.0,it*SAMPLE_RATE.toDouble()/TransferEstimator.SIZE)/100)).toInt() }
        val reductions=bands.values.map { min(improvement(single,it),improvement(both,it)) }
        require(a>=MeasurementLimits.IMPROVEMENT_DB && b>=MeasurementLimits.IMPROVEMENT_DB && reductions.all {it>=-MeasurementLimits.MAX_WORSENING_DB}) { "効果不足・未反映: 片側比 $a dB / 両側比 $b dB" }
        return Verification(a,b,reductions,valid.any {corrected.snrDb[it]<10})
    }
}
