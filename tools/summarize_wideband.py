"""Export only derived, public fields from the shared Kotlin designer's index.
Usage: python tools/summarize_wideband.py build/wideband-audit/offline docs/analysis
No design algorithm, raw diagnostics, PCM or device identifiers are copied.
"""
import csv
import json
import sys
from pathlib import Path


def write_csv(path, rows, fields):
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def summarize(source, output):
    data = json.loads((source / "index.json").read_text(encoding="utf-8"))
    output.mkdir(parents=True, exist_ok=True)
    sources, candidates, predictions, subbands, curves = [], [], [], [], []
    for s in data["sessions"]:
        settings, timing = s.get("settings", {}), s.get("timing", {})
        sources.append(dict(session=s["session"], group=s["group"], file=s["file"], source_sha256=s.get("sourceSha256"),
                            low_hz=settings.get("lowHz"), high_hz=settings.get("highHz"), master_db=s.get("masterDb"),
                            input_rate=settings.get("inputRate"), preset=settings.get("preset"), signal=settings.get("signal"),
                            seconds=settings.get("seconds"), tail_seconds=settings.get("tailSeconds"), latency_seconds=settings.get("latencySeconds"),
                            timing_reliable=timing.get("reliable"), residual_48k=timing.get("residual48k"), clock_ppm=timing.get("clockPpm"),
                            timing_findings="|".join(v["code"] for v in timing.get("findings", [])), stage=s.get("originalStage"),
                            original_reason=s.get("originalReasonCode"), original_coverage=s.get("originalCoverage"), original_eligible=s.get("originalEligible"), failure=s.get("failure", "")))
        for c in s.get("candidates", []):
            r = c["result"]
            base = dict(session=s["session"], group=s["group"], candidate_id=c["id"], coefficient_sha256=c["coefficientHash"], method=c["method"])
            candidates.append(dict(base, source_sha256=s["sourceSha256"], generated=c["generated"], failure=r.get("failure", ""),
                                   taps=c["taps"], regularization=c["regularization"], master_db=c["masterDb"], boost_limit_db=c["maximumBoostDb"],
                                   boost_upper_db=r.get("maxBoostDb"), dsp_peak_bound=r.get("dspPeakBound"),
                                   design_bins=r["designBinCount"], requested_bins=r["requestedBinCount"], design_fraction=r["designFraction"],
                                   trial_coverage=r["trialCoverage"], runs=json.dumps(r["runs"], separators=(",", ":"))))
            for p in r.get("predictions", []):
                row = dict(base, evaluation_repeat=p["evaluationRepeat"])
                predictions.append(dict(row, **{f"{name}_{key}": p[obj][key] for name, obj in [("full", "fullBand"), ("design", "designBins")]
                                               for key in ["singleDb", "bothDb", "singlePower", "bothPower", "candidatePower", "lowSnrBins"]}))
                for b in p["subbands"]:
                    subbands.append(dict(row, low_hz=b["lowHz"], high_hz=b["highHz"], boundary_rule=b["boundaryRule"],
                                         bin_indices="|".join(map(str, b["bins"])), **b["prediction"]))
                for i, hz in enumerate(p["hz"]):
                    curves.append(dict(row, hz=hz, single_db=p["singleDb"][i], both_db=p["bothDb"][i], low_snr=p["lowSnr"][i], outside_design=p["outsideDesign"][i]))
    for name, rows in [("sources", sources), ("candidates", candidates), ("predictions", predictions), ("subbands", subbands), ("frequencies", curves)]:
        write_csv(output / f"wideband-{name}.csv", rows, list(rows[0]) if rows else ["no_data"])
    print(f"{len(sources)} sources / {len(candidates)} candidates / {len(predictions)} predictions / {len(subbands)} subbands / {len(curves)} frequency rows")
    for c in candidates:
        print(c["group"], c["session"], c["method"], c["candidate_id"], c["generated"], c["design_bins"], c["failure"])


if __name__ == "__main__":
    summarize(Path(sys.argv[1]), Path(sys.argv[2]))
