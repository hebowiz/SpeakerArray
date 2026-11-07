"""Summarize the shared Kotlin designer output; never designs a filter or copies raw diagnostics.
Usage: python tools/summarize_experimental.py build/experimental-audit/offline docs/analysis
"""
import csv
import json
import sys
from pathlib import Path


def summarize(source, output):
    output.mkdir(parents=True, exist_ok=True)
    index = json.loads((source / "index.json").read_text(encoding="utf-8"))
    rows, curves = [], []
    for session in index["sessions"]:
        for candidate in session.get("candidates", []):
            report = candidate["result"]
            base = dict(session=session["session"], source_sha256=session["sourceSha256"], candidate_id=candidate["id"],
                        coefficient_sha256=candidate["coefficientHash"], method=candidate["method"], generated=candidate["generated"],
                        failure=report.get("failure", ""), taps=candidate["taps"], regularization=candidate["regularization"],
                        master_db=candidate["masterDb"], boost_limit_db=candidate["maximumBoostDb"],
                        boost_upper_db=report.get("maxBoostDb"), dsp_peak_bound=report.get("dspPeakBound"),
                        design_bins=report["designBinCount"], requested_bins=report["requestedBinCount"],
                        trial_coverage=report["trialCoverage"], timing_reliable=report["timing"]["reliable"],
                        timing_findings="|".join(f["code"] for f in report["timing"]["findings"]))
            for prediction in report.get("predictions", [{}]):
                row = dict(base, evaluation_repeat=prediction.get("evaluationRepeat"))
                for name, key in [("full", "fullBand"), ("design", "designBins")]:
                    for field in ["singleDb", "bothDb", "lowSnrBins"]:
                        row[name + "_" + field] = prediction.get(key, {}).get(field)
                rows.append(row)
                for i, hz in enumerate(prediction.get("hz", [])):
                    curves.append(dict(session=session["session"], candidate_id=candidate["id"], method=candidate["method"],
                                       evaluation_repeat=prediction["evaluationRepeat"], hz=hz,
                                       single_db=prediction["singleDb"][i], both_db=prediction["bothDb"][i],
                                       low_snr=prediction["lowSnr"][i], outside_design=prediction["outsideDesign"][i]))
    for name, values in [("experimental-fir-summary.csv", rows), ("experimental-fir-predictions.csv", curves)]:
        with (output / name).open("w", encoding="utf-8", newline="") as f:
            if values:
                writer = csv.DictWriter(f, fieldnames=list(values[0]))
                writer.writeheader()
                writer.writerows(values)
    print(f"{len(rows)} evaluation rows; {len(curves)} predicted frequency rows")


if __name__ == "__main__":
    summarize(Path(sys.argv[1]), Path(sys.argv[2]))
