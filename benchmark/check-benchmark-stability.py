#!/usr/bin/env python3

import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the stability of Gradle Profiler measured-build samples."
    )
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-samples", required=True, type=int)
    parser.add_argument("--max-relative-mad-percent", required=True, type=float)
    parser.add_argument("--max-half-drift-percent", required=True, type=float)
    parser.add_argument("--outlier-threshold-percent", required=True, type=float)
    parser.add_argument("--max-outlier-count", required=True, type=int)
    return parser.parse_args()


def read_measured_samples(csv_file: Path) -> tuple[list[float], str | None]:
    samples = []
    with csv_file.open(newline="", encoding="utf-8") as input_file:
        for row in csv.reader(input_file):
            if not row or not row[0].startswith("measured build"):
                continue

            expected_label = f"measured build #{len(samples) + 1}"
            if row[0] != expected_label or len(row) < 2:
                return samples, "invalid_or_nonsequential_samples"

            try:
                sample = float(row[1])
            except ValueError:
                return samples, "invalid_or_nonsequential_samples"

            if not math.isfinite(sample) or sample <= 0:
                return samples, "invalid_or_nonsequential_samples"
            samples.append(sample)

    return samples, None


def main() -> int:
    args = parse_args()
    samples, reason = read_measured_samples(args.csv_file)

    median_ms = None
    relative_mad_percent = None
    early_median_ms = None
    late_median_ms = None
    half_drift_percent = None
    outlier_count = None

    if reason is None and len(samples) != args.expected_samples:
        reason = "unexpected_sample_count"
    if reason is None and len(samples) % 2 != 0:
        reason = "sample_count_must_be_even"

    if reason is None:
        median_ms = statistics.median(samples)
        deviations = [abs(sample - median_ms) for sample in samples]
        relative_mad_percent = statistics.median(deviations) / median_ms * 100

        half = len(samples) // 2
        early_median_ms = statistics.median(samples[:half])
        late_median_ms = statistics.median(samples[half:])
        half_drift_percent = abs(late_median_ms - early_median_ms) / median_ms * 100
        outlier_count = sum(
            abs(sample - median_ms) / median_ms * 100 > args.outlier_threshold_percent
            for sample in samples
        )

        if relative_mad_percent > args.max_relative_mad_percent:
            reason = "relative_mad_exceeds_limit"
        elif half_drift_percent > args.max_half_drift_percent:
            reason = "half_drift_exceeds_limit"
        elif outlier_count > args.max_outlier_count:
            reason = "outlier_count_exceeds_limit"

    result = {
        "status": "pass" if reason is None else "fail",
        "reason": reason,
        "sampleCount": len(samples),
        "samplesMs": samples,
        "medianMs": median_ms,
        "relativeMadPercent": relative_mad_percent,
        "earlyMedianMs": early_median_ms,
        "lateMedianMs": late_median_ms,
        "halfDriftPercent": half_drift_percent,
        "outlierCount": outlier_count,
        "limits": {
            "expectedSamples": args.expected_samples,
            "maxRelativeMadPercent": args.max_relative_mad_percent,
            "maxHalfDriftPercent": args.max_half_drift_percent,
            "outlierThresholdPercent": args.outlier_threshold_percent,
            "maxOutlierCount": args.max_outlier_count,
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return 0 if reason is None else 1


if __name__ == "__main__":
    sys.exit(main())
