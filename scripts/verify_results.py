"""Independent CSV reconciliation; Python standard library only, no broker required."""
from pathlib import Path
from collections import defaultdict
import csv
import hashlib
import json
import math
import statistics
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
folder = (root / (sys.argv[1] if len(sys.argv) > 1 else "results/local")).resolve()


def read(name):
    with (folder / name).open(encoding="utf-8", newline="") as file:
        return list(csv.DictReader(file))


def close(actual, expected, tolerance=2e-5):
    assert math.isfinite(float(actual))
    assert abs(float(actual) - expected) <= tolerance, (actual, expected)


rows = read("measurements.csv")
shares = defaultdict(list)
for row in read("shares.csv"):
    shares[row["experiment"], row["phase"], row["run"]].append(row)

groups = defaultdict(list)
for row in rows:
    key = row["experiment"], row["phase"], row["run"]
    assert key in shares
    sent, received = int(row["sent"]), int(row["received"])
    assert row["result"] == "PASS" and not row["error"], row
    assert sent == received == int(row["unique"])
    for field in ("lost", "duplicates", "unexpected", "corrupted"):
        assert int(row[field]) == 0
    assert len(shares[key]) == int(row["receivers"])
    assert sum(int(r["count"]) for r in shares[key]) == received
    close(sum(float(r["share_percent"]) for r in shares[key]), 100)
    for receiver in shares[key]:
        close(receiver["share_percent"], 100 * int(receiver["count"]) / received)
    if row["scenario"] == "2":
        assert [int(r["count"]) for r in shares[key]] == [sent // 4, sent * 3 // 4]
    time = float(row["time_seconds"])
    assert 0 < time <= 10
    close(row["throughput"], received / time)
    close(row["bytes_per_second"], received * int(row["payload_bytes"]) / time)
    ideal = 100 / int(row["receivers"])
    close(row["deviation_pp"], max(abs(float(r["share_percent"]) - ideal) for r in shares[key]))
    assert 0 <= float(row["latency_median_ms"]) <= float(row["latency_p95_ms"])
    groups[row["experiment"]].append(row)

summaries = {r["experiment"]: r for r in read("summary.csv")}
assert groups.keys() == summaries.keys()
fields = {"median_seconds": "time_seconds", "median_msg_s": "throughput",
          "median_bytes_s": "bytes_per_second", "median_D_pp": "deviation_pp",
          "median_latency_ms": "latency_median_ms", "median_p95_latency_ms": "latency_p95_ms"}
for name, values in groups.items():
    measured = [r for r in values if r["phase"] == "measurement"]
    assert len(measured) == 5 and len(values) == 6, name
    assert len({(r["phase"], r["run"]) for r in values}) == 6
    summary = summaries[name]
    assert summary["result"] == "PASS" and int(summary["runs"]) == 5
    for destination, source in fields.items():
        close(summary[destination], statistics.median(float(r[source]) for r in measured))
    close(summary["min_seconds"], min(float(r["time_seconds"]) for r in measured))
    close(summary["max_seconds"], max(float(r["time_seconds"]) for r in measured))

expected = {"scenario-1", "scenario-2", "scenario-3", "load-4x4"}
expected |= {f"receivers-{n}" for n in (1, 2, 3, 4, 6)}
expected |= {f"bytes-{n}" for n in (16, 256, 4096, 65536)}
expected |= {"nodelay-true", "nodelay-false"}
assert set(groups) == expected and len(rows) == 90
environment = dict(line.split("=", 1) for line in (folder / "environment.txt").read_text(encoding="utf-8").splitlines())
assert environment["lab_threads_after_close"] == "0" and environment["protocol_errors"] == "0"

manifest = {
    "benchmark_source_commit": subprocess.check_output(["git", "-c", f"safe.directory={root.as_posix()}", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "runs": len(rows), "configurations": len(groups), "measured_runs": 75,
    "all_passed": True, "all_delivery_and_summary_metrics_reconciled": True,
    "sha256": {str(p.relative_to(root)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest()
               for p in [*sorted((root / "src/main/java").rglob("*.java")), folder / "measurements.csv", folder / "shares.csv", folder / "summary.csv"]},
}
(folder / "verification.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"PASS: {len(rows)} runs, {len(groups)} configurations; counts, shares, rates and medians reconciled.")
