"""Read archived candidate JSONs and produce a relative-power table; no acoustic inference.
Usage: python tools/summarize_comparisons.py output.csv archive1.json archive2.json ...
Repeated run IDs must have identical contents. Legacy records without IDs stay separately identified.
"""
import csv
import hashlib
import json
import sys
from pathlib import Path


def rows_from(paths):
    seen, rows = {}, []
    for path in paths:
        raw = path.read_bytes()
        data = json.loads(raw.decode("utf-8-sig"))
        if data.get("type") != "speakerarray-experimental-fir":
            raise ValueError("An exported experimental candidate is required")
        candidate = data["candidate"]
        for index, result in enumerate(candidate["comparisons"]):
            if result["candidateId"] != candidate["id"] or result["coefficientHash"] != candidate["coefficientHash"] or result["formalAccepted"]:
                raise ValueError("Candidate/hash mismatch or formal result")
            run_id = result.get("runId") or f"legacy-file-{hashlib.sha256(raw).hexdigest()}-{index}"
            identity = json.dumps(result, sort_keys=True, ensure_ascii=False)
            if run_id in seen:
                if seen[run_id] != identity:
                    raise ValueError("Conflicting contents for the same run ID")
                continue
            seen[run_id] = identity
            plan = result.get("plan", {})
            row = dict(file=path.name, candidate_id=candidate["id"], coefficient_sha256=candidate["coefficientHash"],
                       run_id=run_id, started_at_epoch_ms=result.get("startedAtEpochMs"), ended_at_epoch_ms=result.get("endedAtEpochMs"),
                       stage=result.get("stage"), outcome=result.get("outcome"), status=result.get("status"),
                       master_db=result.get("masterDb"), low_hz=plan.get("lowHz"), high_hz=plan.get("highHz"))
            for prefix, value in [("full", result)] + [(f"band_{int(b['lowHz'])}_{int(b['highHz'])}", b) for b in result.get("subbands", [])]:
                for field in ("noiseFloorPower", "noiseDriftDb", "baselineDriftDb", "candidateSpreadDb", "evaluable", "stable"):
                    row[f"{prefix}_{field}"] = value.get(field)
                for base in ("single", "both"):
                    for field in ("pointDb", "lowerDb", "evaluable", "noiseLimited"):
                        row[f"{prefix}_{base}_{field}"] = value.get(base, {}).get(field)
            rows.append(row)
    return rows


if __name__ == "__main__":
    rows = rows_from([Path(p) for p in sys.argv[2:]])
    fields = list(dict.fromkeys(key for row in rows for key in row))
    with Path(sys.argv[1]).open("w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=fields or ["no_runs"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"{len(rows)} distinct runs; missing measurements remain empty")
