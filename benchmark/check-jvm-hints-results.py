#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0
"""Apply Metro's stability gate to every scenario in a Gradle Profiler CSV."""

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.csv_file.open(newline="", encoding="utf-8") as source:
        rows = list(csv.reader(source))
    if not rows or rows[0][0] != "scenario":
        parser.error("expected a Gradle Profiler CSV with a scenario header")
    args.output.mkdir(parents=True, exist_ok=True)
    checker = Path(__file__).with_name("check-benchmark-stability.py")
    results = {}
    for column, scenario in enumerate(rows[0][1:], start=1):
        csv_file = args.output / f"{scenario}.csv"
        json_file = args.output / f"{scenario}.json"
        with csv_file.open("w", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            writer.writerows((row[0], row[column]) for row in rows if row)
        json_file.unlink(missing_ok=True)
        subprocess.run(
            [
                sys.executable, str(checker), str(csv_file), "--output", str(json_file),
                "--expected-samples", "10",
                "--max-relative-mad-percent", "10",
                "--max-half-drift-percent", "10",
                "--outlier-threshold-percent", "20",
                "--max-outlier-count", "1",
            ],
            check=False,
        )
        results[scenario] = json.loads(json_file.read_text(encoding="utf-8"))
    (args.output / "summary.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    for scenario, result in results.items():
        print(f"{scenario}: {result['medianMs']} ms; {result['status']} ({result['reason']})")
    return 0 if all(result["status"] == "pass" for result in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
