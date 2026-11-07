"""Offline, standard-library-only analysis. No PCM or personal route metadata exported.
Run: python tools/analyze_diagnostics.py build/practical-audit docs/analysis
"""
import argparse
import cmath
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path
from collections import Counter

STEP = 48000 / 8192
REASONS = [f'{c}_{r}' for c in ('LEFT', 'RIGHT') for r in ('SNR', 'REPEAT_GAIN', 'REPEAT_PHASE')]


def percentile(values, q):
    v = sorted(values)
    if not v:
        return None
    p = (len(v)-1)*q
    i = int(p)
    return v[i] + (v[min(i+1, len(v)-1)]-v[i])*(p-i)


def stats(v):
    return dict(median=percentile(v, .5), p90=percentile(v, .9), maximum=max(v) if v else None)


def runs(bins):
    result = []
    for k in bins:
        if not result or k != result[-1][-1]+1:
            result.append([k])
        else:
            result[-1].append(k)
    return result


def phase_fit(freq, a, b, snr):
    """Only longest contiguous identifiable run; never unwrap across a low-SNR gap.
    Adjacent principal increments >=90 degrees split a run as ambiguous.
    Delay is descriptive within that run, not an absolute delay identification.
    """
    good = [i for i in range(len(freq)) if snr[i] >= 20 and min(abs(a[i]), abs(b[i])) > 1e-8]
    segments = []
    for group in runs(good):
        part = []
        for i in group:
            p = cmath.phase(b[i]/a[i])
            if part and abs(cmath.phase(cmath.exp(1j*(p-part[-1][1])))) >= math.pi/2:
                segments.append(part)
                part = []
            if part:
                p = part[-1][1] + cmath.phase(cmath.exp(1j*(p-part[-1][1])))
            part.append((i, p))
        if part:
            segments.append(part)
    part = max(segments, key=len, default=[])
    if len(part) < 8:
        return dict(usable=False, eligibleBins=len(good), fittedBins=len(part))
    x = [freq[i] for i, _ in part]
    y = [p*180/math.pi for _, p in part]
    mx, my = sum(x)/len(x), sum(y)/len(y)
    slope = sum((v-mx)*(w-my) for v, w in zip(x, y))/sum((v-mx)**2 for v in x)
    intercept = my-slope*mx
    residual = [w-intercept-slope*v for v, w in zip(x, y)]
    return dict(usable=True, eligibleBins=len(good), fittedBins=len(part), segments=len(segments),
                lowHz=min(x), highHz=max(x), delaySamples=-slope*48000/360,
                interceptDegrees=intercept, residualAbsDegrees=stats(list(map(abs, residual))),
                residualRmsDegrees=math.sqrt(sum(v*v for v in residual)/len(residual)))


def failures(q, k, gain, phase):
    return [f'{channel}_{reason}' for channel, short in [('LEFT', 'left'), ('RIGHT', 'right')]
            for reason, fail in [('SNR', q[short]['snrDb'][k] < 20),
                                 ('REPEAT_GAIN', q[short+'RepeatDb'][k] > gain),
                                 ('REPEAT_PHASE', q[short+'RepeatPhaseDeg'][k] > phase)] if fail]


def analyze(d, name, digest):
    q, s = d['quality'], d['settings']
    bins = [k for k in range(len(q['valid'])) if s['lowHz'] <= k*STEP <= s['highHz']]
    n = len(bins)
    fail = [failures(q, k, 1, 5) for k in bins]
    overlap = Counter('+'.join(sorted(f)) or 'PASS' for f in fail)
    result = dict(session=name, sha256=digest,
                  group='prepared-main' if 'ready-500-1000' in name else 'prepared-wide' if 'ready' in name else 'before-readiness',
                  bins=n, lowHz=s['lowHz'], highHz=s['highHz'], masterDb=d['masterDb'],
                  timingReliable=d['timing']['reliable'], timingFindings=d['timing']['findings'],
                  failures={r:dict(rate=sum(r in f for f in fail)/n, sole=sum(f == [r] for f in fail)/n) for r in REASONS},
                  overlaps={k:v/n for k, v in sorted(overlap.items())}, channels={})
    result['coverage'] = {label:sum(not any(r.endswith(suffixes) for r in f) for f in fail)/n
                          for label, suffixes in [('snr', ('SNR',)), ('snrGain', ('SNR', 'GAIN')),
                                                  ('snrPhase', ('SNR', 'PHASE')), ('all', ('SNR', 'GAIN', 'PHASE'))]}
    result['sensitivity'] = [dict(gainDb=g, phaseDegrees=p, coverage=len(ok)/n,
                                 continuousBandsHz=[[group[0]*STEP, group[-1]*STEP, len(group)] for group in runs(ok)])
                             for g, p in itertools.product([1, 2, 3], [5, 10, 15, 20])
                             for ok in [[k for k in bins if not failures(q, k, g, p)]]]
    transfers = d['initialTransfers']
    h = [[complex(t['real'][k], t['imaginary'][k]) for k in bins] for t in transfers]
    for ci, channel in enumerate(['left', 'right']):
        a, b = h[ci*2:ci*2+2]
        gain = [20*math.log10(max(abs(y), 1e-20)/max(abs(x), 1e-20)) for x, y in zip(a, b)]
        snr = [min(transfers[ci*2]['snrDb'][k], transfers[ci*2+1]['snrDb'][k]) for k in bins]
        fit = phase_fit([k*STEP for k in bins], a, b, snr)
        pilots = d['initialTiming']['pilots']
        # L1,R1,L2,R2, with bracketing pilots for each sweep.
        residual_means = [sum(p['signedResidual48k'] for p in pilots[j*2:j*2+2])/2 for j in range(4)]
        fit['pilotRepeatDeltaSamples'] = residual_means[ci+2]-residual_means[ci]
        result['channels'][channel] = dict(snrDb=stats(snr), repeatGainDb=stats([q[channel+'RepeatDb'][k] for k in bins]),
            repeatPhaseDegrees=stats([q[channel+'RepeatPhaseDeg'][k] for k in bins]), phaseFit=fit,
            signedGainDb=stats(gain), gainResidualP90Db=percentile([abs(v-percentile(gain, .5)) for v in gain], .9))
    valid = [i for i, k in enumerate(bins) if min(t['snrDb'][k] for t in transfers) >= 20 and min(abs(v[i]) for v in h)>1e-8]
    ratios = [[h[2+j][i]/h[j][i] for i in valid] for j in range(2)]
    result['ratioRepeat'] = dict(bins=len(valid), gainDb=stats([abs(20*math.log10(abs(b/a))) for a,b in zip(*ratios)]),
        phaseDegrees=stats([abs(cmath.phase(b/a)*180/math.pi) for a,b in zip(*ratios)]))
    predictions = []
    for train, test in [(0, 1), (1, 0)]:
        selected = [i for i in valid if abs(h[train][i]/h[2+train][i]) <= 10**(6/20)]
        residual = sum(abs(h[test][i]-h[train][i]/h[2+train][i]*h[2+test][i])**2 for i in selected)
        single = sum(abs(h[test][i])**2 for i in selected)
        both = sum(abs(h[test][i]+h[2+test][i])**2 for i in selected)
        predictions.append(dict(train=train+1, test=test+1, bins=len(selected),
            singleDb=10*math.log10(max(single, 1e-30)/max(residual, 1e-30)),
            bothDb=10*math.log10(max(both, 1e-30)/max(residual, 1e-30))))
    result['idealBinwisePredictionNotCausalFirOrMeasurement'] = predictions
    distribution = [dict(session=name, hz=k*STEP, leftSnr=q['left']['snrDb'][k], rightSnr=q['right']['snrDb'][k],
        leftGain=q['leftRepeatDb'][k], rightGain=q['rightRepeatDb'][k], leftPhase=q['leftRepeatPhaseDeg'][k], rightPhase=q['rightRepeatPhaseDeg'][k],
        strictReasons='+'.join(failures(q,k,1,5)),
        leftSignedPhase=cmath.phase(h[1][i]/h[0][i])*180/math.pi if abs(h[0][i])>1e-8 else None,
        rightSignedPhase=cmath.phase(h[3][i]/h[2][i])*180/math.pi if abs(h[2][i])>1e-8 else None,
        leftSignedGain=20*math.log10(max(abs(h[1][i]),1e-20)/max(abs(h[0][i]),1e-20)),
        rightSignedGain=20*math.log10(max(abs(h[3][i]),1e-20)/max(abs(h[2][i]),1e-20))) for i,k in enumerate(bins)]
    return result, distribution


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    results, rows, skipped = [], [], []
    # Content detection, not only a filename pattern. Identical exports are deduplicated.
    seen = set()
    for path in sorted(args.input.rglob('*.json')):
        raw = path.read_bytes()
        try:
            d = json.loads(raw.decode('utf-8-sig'))
        except (ValueError, UnicodeError):
            continue
        if not isinstance(d, dict) or d.get('type') != 'speakerarray-diagnostic':
            continue
        digest = hashlib.sha256(raw).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        if 'quality' not in d or 'initialTransfers' not in d:
            skipped.append(dict(file=path.name, reason='no repeated transfer arrays'))
            continue
        result, distribution = analyze(d, path.stem, digest)
        results.append(result)
        rows.extend(distribution)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'diagnostic-summary.json').write_text(json.dumps(dict(sessions=results, skipped=skipped), indent=2, ensure_ascii=False), encoding='utf-8')
    if rows:
        with (args.output/'frequency-distribution.csv').open('w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    lines = ['# Diagnostic sensitivity (generated)', '', 'Percent of requested bins; contiguous intervals include singleton bins.', '',
             '| Session | Group | SNR only % | + gain % | + phase % | strict % | trial 2 dB/20° % | timing reliable |',
             '|---|---|---:|---:|---:|---:|---:|---|']
    for r in results:
        c=r['coverage']; trial=next(x for x in r['sensitivity'] if x['gainDb']==2 and x['phaseDegrees']==20)
        lines.append(f"| {r['session']} | {r['group']} | {c['snr']*100:.2f} | {c['snrGain']*100:.2f} | {c['snrPhase']*100:.2f} | {c['all']*100:.2f} | {trial['coverage']*100:.2f} | {r['timingReliable']} |")
    for r in results:
        lines += ['', '## '+r['session'], '', '| Gain dB | Phase ° | Coverage % | Continuous bands Hz (bin count) |', '|---:|---:|---:|---|']
        for v in r['sensitivity']:
            bands='; '.join(f'{a:.2f}–{b:.2f} ({n})' for a,b,n in v['continuousBandsHz']) or 'none'
            lines.append(f"| {v['gainDb']} | {v['phaseDegrees']} | {100*v['coverage']:.2f} | {bands} |")
    detail=['# Repeated response analysis (generated)', '']
    for r in results:
        detail += ['## '+r['session'], '', '| Channel | SNR median/P90/max dB | Gain difference median/P90/max dB | Phase difference median/P90/max ° |', '|---|---|---|---|']
        def triple(v):
            return '/'.join(f'{v[k]:.3f}' for k in ['median','p90','maximum'])
        for c,v in r['channels'].items():
            detail.append(f"| {c} | {triple(v['snrDb'])} | {triple(v['repeatGainDb'])} | {triple(v['repeatPhaseDegrees'])} |")
        detail += ['', '| Failure | All occurrences % | Sole failure % |', '|---|---:|---:|']
        for code,v in r['failures'].items():
            detail.append(f"| {code} | {100*v['rate']:.3f} | {100*v['sole']:.3f} |")
        detail += ['', '| Exact overlap | Requested bins % |', '|---|---:|']
        detail += [f'| {code} | {100*rate:.3f} |' for code,rate in r['overlaps'].items()]
        detail += ['', '| Channel | Fitted Hz | Bins used/eligible | Delay samples | Intercept ° | Residual RMS ° | Pilot repeat delta samples | Signed gain median dB | Gain residual P90 dB |', '|---|---|---|---:|---:|---:|---:|---:|---:|']
        for c,v in r['channels'].items():
            f=v['phaseFit']
            if f['usable']:
                detail.append(f"| {c} | {f['lowHz']:.2f}–{f['highHz']:.2f} | {f['fittedBins']}/{f['eligibleBins']} | {f['delaySamples']:.3f} | {f['interceptDegrees']:.3f} | {f['residualRmsDegrees']:.3f} | {f['pilotRepeatDeltaSamples']:.3f} | {v['signedGainDb']['median']:.3f} | {v['gainResidualP90Db']:.3f} |")
            else:
                detail.append(f'| {c} | insufficient contiguous data | | | | | | | |')
        v=r['ratioRepeat']
        detail += ['', f"Sequential L/R ratio difference ({v['bins']} bins): gain {triple(v['gainDb'])} dB; phase {triple(v['phaseDegrees'])} °.", '',
                   '| Training repeat → held-out repeat | bins | Predicted single reduction dB | Predicted both reduction dB |', '|---|---:|---:|---:|']
        for v in r['idealBinwisePredictionNotCausalFirOrMeasurement']:
            detail.append(f"| {v['train']} → {v['test']} | {v['bins']} | {v['singleDb']:.3f} | {v['bothDb']:.3f} |")
    (args.output/'repeated-response.md').write_text('\n'.join(detail)+'\n',encoding='utf-8')
    (args.output/'sensitivity.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print(f'{len(results)} sessions, {len(rows)} frequency rows, {len(skipped)} missing arrays')


if __name__ == '__main__':
    main()
