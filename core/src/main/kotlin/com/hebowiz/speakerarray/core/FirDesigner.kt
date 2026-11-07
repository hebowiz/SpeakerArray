package com.hebowiz.speakerarray.core

import kotlin.math.*

/** Radix-2 FFT; all work arrays are owned by the caller. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val r = re[i]; re[i] = re[j]; re[j] = r; val t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val angle = (if (inverse) 2 else -2) * PI / len
            val wr = cos(angle); val wi = sin(angle)
            var start = 0
            while (start < n) {
                var ur = 1.0; var ui = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k; val b = a + len / 2
                    val vr = re[b]*ur - im[b]*ui; val vi = re[b]*ui + im[b]*ur
                    re[b] = re[a] - vr; im[b] = im[a] - vi; re[a] += vr; im[a] += vi
                    val t = ur*wr - ui*wi; ui = ur*wi + ui*wr; ur = t
                }
                start += len
            }
            len *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] = re[i]/n; im[i] = im[i]/n }
    }
}
object FirDesigner {
    fun validate(spec: FirSpec, n: Int) {
        require(n in listOf(257, 513, 1025))
        require(spec.eq.size == EQ_HZ.size) { "EQは10点です" }
        spec.eq.forEach { finiteRange(it, -12.0, 12.0, "EQ dB") }
        finiteRange(spec.low, 1.0, 23999.0, "遮断周波数 Hz")
        finiteRange(spec.high, 1.0, 23999.0, "遮断周波数 Hz")
        if (spec.mode == FirMode.BPF) require(spec.low < spec.high) { "BPFは下限 < 上限です" }
        if (spec.mode == FirMode.COEFFICIENTS) {
            require(spec.coefficients.size in 1..n) { "係数は1～${n}個です" }
            require(spec.coefficients.all { it.isFinite() && abs(it) <= 1e6 }) { "係数は有限値かつ絶対値1000000以下です" }
        }
    }
    fun flat(n: Int) = DoubleArray(n).also { it[n/2] = 1.0 }
    fun targetDb(spec: FirSpec, f: Double): Double {
        if (spec.mode != FirMode.EQ) return when (spec.mode) {
            FirMode.LPF -> if (f <= spec.high) 0.0 else -80.0
            FirMode.HPF -> if (f >= spec.low) 0.0 else -80.0
            FirMode.BPF -> if (f in spec.low..spec.high) 0.0 else -80.0
            else -> 0.0
        }
        if (f <= EQ_HZ.first()) return spec.eq.first()
        if (f >= EQ_HZ.last()) return spec.eq.last()
        val i = EQ_HZ.indexOfFirst { it >= f } - 1
        val t = ln(f/EQ_HZ[i]) / ln(EQ_HZ[i+1]/EQ_HZ[i])
        return spec.eq[i] * (1-t) + spec.eq[i+1] * t
    }
    fun design(spec: FirSpec, n: Int): DoubleArray {
        validate(spec, n)
        if (spec.mode == FirMode.FLAT) return flat(n)
        if (spec.mode == FirMode.COEFFICIENTS) return DoubleArray(n) { spec.coefficients.getOrElse(it) { 0.0 } }
        val d = n/2
        if (spec.mode == FirMode.EQ) {
            val size = 16384
            val re = DoubleArray(size) { k -> dbGain(targetDb(spec, min(k, size-k).toDouble()*SAMPLE_RATE/size)) }
            val im = DoubleArray(size)
            Fft.transform(re, im, true)
            return DoubleArray(n) { i -> re[(i-d+size)%size] * blackman(i, n) }
        }
        fun lowpass(f: Double, m: Int) = if (m == 0) 2*f/SAMPLE_RATE else sin(2*PI*f*m/SAMPLE_RATE)/(PI*m)
        return DoubleArray(n) { i ->
            val m = i-d
            val x = when (spec.mode) {
                FirMode.LPF -> lowpass(spec.high, m)
                FirMode.HPF -> (if (m == 0) 1.0 else 0.0) - lowpass(spec.low, m)
                else -> lowpass(spec.high, m) - lowpass(spec.low, m)
            }
            x * blackman(i, n)
        }
    }
    fun blackman(i: Int, n: Int) = 0.42 - 0.5*cos(2*PI*i/(n-1)) + 0.08*cos(4*PI*i/(n-1))
}

object FractionalDelay {
    const val BASE = 16
    /** 33 tap windowed sinc, common 16-sample reference even for integer delays. */
    fun kernel(samples: Double): DoubleArray {
        require(samples.isFinite() && samples in 0.0..480.0)
        val whole = floor(samples).toInt(); val fraction = samples-whole
        val result = DoubleArray(whole+33)
        for (i in 0..32) {
            val x = i-BASE-fraction
            result[whole+i] = (if (abs(x) < 1e-12) 1.0 else sin(PI*x)/(PI*x)) * FirDesigner.blackman(i, 33)
        }
        val sum = result.sum()
        for (i in result.indices) result[i] /= sum
        return result
    }
}

object ResponseAnalyzer {
    fun response(h: DoubleArray, hz: Double): Complex {
        var r = 0.0; var j = 0.0
        val w = -2*PI*hz/SAMPLE_RATE
        h.forEachIndexed { i, value -> r += value*cos(w*i); j += value*sin(w*i) }
        return Complex(r, j)
    }
    fun channel(p: Processing, right: Boolean, f: Double, sine: Boolean): Complex {
        val ch = if (right) p.right else p.left
        if (ch.muted) return Complex(0.0, 0.0)
        val h = if (ch.enabled) FirDesigner.design(ch.fir, p.length) else FirDesigner.flat(p.length)
        val delay = max(0.0, if (right) p.delayUs else -p.delayUs)*SAMPLE_RATE/1e6
        return response(h, f) * response(FractionalDelay.kernel(delay), f) *
            Complex.polar(if (sine && right) p.phaseDeg*PI/180 else 0.0) * dbGain(ch.gainDb) * (if (ch.inverted) -1.0 else 1.0)
    }
    fun pattern(session: Session): DoubleArray {
        val layout=session.activeLayout ?: return DoubleArray(181) {Double.NaN}
        val f = session.previewFrequencyHz
        val l = channel(session.effectiveProcessing(), false, f, session.source.kind == SourceKind.SINE)
        val r = channel(session.effectiveProcessing(), true, f, session.source.kind == SourceKind.SINE)
        return DoubleArray(181) { i ->
            val g = (if(layout.reversed) -1 else 1)*PI*f/session.soundSpeed * layout.spacingMm/1000 * sin((i-90)*PI/180)
            max(-60.0, levelDb((l*Complex.polar(-g) + r*Complex.polar(g)).magnitude/2))
        }
    }
    data class Curve(val hz: DoubleArray, val db: DoubleArray, val phase: DoubleArray, val groupMs: DoubleArray)
    fun curve(h: DoubleArray, count: Int = 256): Curve {
        val hz = DoubleArray(count) { 20.0 * 1000.0.pow(it.toDouble()/(count-1)) }
        val db = DoubleArray(count); val phase = DoubleArray(count); val group = DoubleArray(count)
        // Unwrap on a dense linear grid before sampling the logarithmic display grid.
        // Unwrapping only the 256 display points aliases long FIR delays above ~1 kHz.
        val gridSize = 32768
        val denseRe = DoubleArray(gridSize); val denseIm = DoubleArray(gridSize)
        System.arraycopy(h, 0, denseRe, 0, h.size)
        Fft.transform(denseRe, denseIm)
        val densePhase = DoubleArray(gridSize/2+1)
        var last = 0.0; var unwrapped = 0.0; var valid = false
        for (i in densePhase.indices) {
            if (hypot(denseRe[i],denseIm[i]) < 1e-4) { densePhase[i] = Double.NaN; valid = false; continue }
            val raw = atan2(denseIm[i],denseRe[i])
            if (!valid) unwrapped = raw else unwrapped += wrapPhase((raw-last)*180/PI)*PI/180
            densePhase[i] = unwrapped; last = raw; valid = true
        }
        for (i in hz.indices) {
            val z = response(h, hz[i]); db[i] = levelDb(z.magnitude)
            if (z.magnitude < 1e-4) { phase[i] = Double.NaN; group[i] = Double.NaN; continue }
            val bin = (hz[i]*gridSize/SAMPLE_RATE).roundToInt()
            phase[i] = densePhase[bin]*180/PI + wrapPhase((z.phase-atan2(denseIm[bin],denseRe[bin]))*180/PI)
            // Analytic derivative avoids unwrap aliasing at high group delays.
            var dr = 0.0; var di = 0.0
            h.forEachIndexed { n, v -> val a = -2*PI*hz[i]*n/SAMPLE_RATE; dr += n*v*sin(a); di -= n*v*cos(a) }
            group[i] = -(di*z.re-dr*z.im)/(z.re*z.re+z.im*z.im)/SAMPLE_RATE*1000
        }
        return Curve(hz, db, phase, group)
    }
}
