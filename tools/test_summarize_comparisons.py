import json
import tempfile
import unittest
from pathlib import Path
from summarize_comparisons import rows_from


class ComparisonArchiveTest(unittest.TestCase):
    def archive(self, path, results):
        path.write_text(json.dumps({"type": "speakerarray-experimental-fir", "candidate":
            {"id": "candidate", "coefficientHash": "hash", "comparisons": results}}), encoding="utf-8")

    def result(self, run_id, **extra):
        return dict(candidateId="candidate", coefficientHash="hash", formalAccepted=False, runId=run_id, **extra)

    def test_archives_deduplicate_runs_preserve_cancel_and_negative_values(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.json", Path(directory) / "b.json"
            measured = self.result("run1", stage="COMPLETED", single={"pointDb": -3.2, "lowerDb": -4.0, "evaluable": True})
            cancelled = self.result("run2", stage="CANCELLED")
            self.archive(a, [measured, cancelled]); self.archive(b, [measured])
            rows = rows_from([a, b])
            self.assertEqual(2, len(rows))
            self.assertEqual(-3.2, rows[0]["full_single_pointDb"])
            self.assertIsNone(rows[1]["full_single_pointDb"])
            self.assertEqual("CANCELLED", rows[1]["stage"])

    def test_conflicting_ids_and_different_candidate_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.json", Path(directory) / "b.json"
            self.archive(a, [self.result("run1", status="one")]); self.archive(b, [self.result("run1", status="two")])
            with self.assertRaises(ValueError): rows_from([a, b])
            self.archive(b, [dict(self.result("run2"), coefficientHash="different")])
            with self.assertRaises(ValueError): rows_from([b])
