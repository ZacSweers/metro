#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Exercise required-check behavior and protect the snapshot publication gates."""

import importlib.util
from pathlib import Path
import re
import unittest

spec = importlib.util.spec_from_file_location("check_ci_status", Path(__file__).with_name("check-ci-status.py"))
status = importlib.util.module_from_spec(spec)
spec.loader.exec_module(status)


def results(full="true", docs="false", idea="true"):
    """Build a successful workflow result with explicit classifier outputs."""
    names = ("changes", "format", "generate-matrix", "core", "compatibility",
             "shaded-compiler-smoke", "js-box", "benchmarks", "samples", "idea-plugin",
             "docs", "kmp-functional", "build-metro", "ide-tests")
    needs = {name: {"result": "success"} for name in names}
    needs["changes"]["outputs"] = {"full": full, "docs": docs, "idea": idea}
    return needs


class CheckCiStatusTest(unittest.TestCase):
    """A skipped dependency must never hide missing selected coverage."""

    def test_full_ci_succeeds(self):
        needs = results()
        needs["docs"]["result"] = "skipped"
        needs["kmp-functional"]["result"] = "skipped"
        self.assertEqual([], status.check_results(needs, "ci"))

    def test_docs_only_allows_expensive_jobs_to_skip(self):
        needs = results(full="false", docs="true", idea="false")
        for name in needs.keys() - {"changes", "format", "docs"}:
            needs[name]["result"] = "skipped"
        self.assertEqual([], status.check_results(needs, "ci"))
        self.assertEqual([], status.check_results(needs, "ide"))

    def test_idea_only_requires_idea_tests(self):
        needs = results(full="false")
        needs["idea-plugin"]["result"] = "skipped"
        self.assertIn("idea-plugin: skipped", status.check_results(needs, "ci"))

    def test_failed_classifier_is_fatal(self):
        needs = results()
        needs["changes"]["result"] = "failure"
        self.assertTrue(status.check_results(needs, "ci"))
        self.assertTrue(status.check_results(needs, "ide"))

    def test_invalid_classifier_output_is_fatal(self):
        needs = results(full="")
        self.assertTrue(status.check_results(needs, "ci"))

    def test_ide_prerequisite_failure_cannot_hide_behind_skipped_tests(self):
        for producer in ("generate-matrix", "build-metro"):
            for result in ("failure", "cancelled", "skipped"):
                with self.subTest(producer=producer, result=result):
                    needs = results()
                    needs[producer]["result"] = result
                    needs["ide-tests"]["result"] = "skipped"
                    self.assertTrue(status.check_results(needs, "ide"))

    def test_missing_or_skipped_compiler_lane_is_fatal(self):
        for result in (None, "skipped", "failure", "cancelled"):
            needs = results()
            needs["compatibility"]["result"] = result
            self.assertTrue(status.check_results(needs, "ci"))

    def test_main_requires_kmp_functional_tests(self):
        needs = results()
        needs["kmp-functional"]["result"] = "skipped"
        self.assertTrue(status.check_results(needs, "ci", is_main=True))

    def test_main_allows_intentionally_skipped_docs(self):
        """Main can publish after selected checks pass with docs unselected."""
        needs = results()
        needs["docs"]["result"] = "skipped"
        self.assertEqual([], status.check_results(needs, "ci", is_main=True))

    def test_main_rejects_unsuccessful_required_checks(self):
        """Every selected check must succeed before the aggregate permits publication."""
        required = ("changes", "format", "generate-matrix", "core", "compatibility",
                    "shaded-compiler-smoke", "js-box", "benchmarks", "samples",
                    "idea-plugin", "kmp-functional")
        for name in required:
            for result in ("failure", "cancelled", "skipped", None):
                with self.subTest(job=name, result=result):
                    needs = results()
                    needs["docs"]["result"] = "skipped"
                    needs[name]["result"] = result
                    self.assertTrue(status.check_results(needs, "ci", is_main=True))

    def test_selected_docs_must_succeed(self):
        """Docs can skip only when the classifier leaves them unselected."""
        for result in ("failure", "cancelled", "skipped", None):
            with self.subTest(result=result):
                needs = results(docs="true")
                needs["docs"]["result"] = result
                self.assertIn(f"docs: {result or 'missing'}", status.check_results(needs, "ci"))


class SnapshotPublishConditionTest(unittest.TestCase):
    """Protect the workflow contract; GitHub evaluates status across the dependency chain."""

    def test_publish_gates(self):
        """Intentional skips require explicit status plus all existing publication gates."""
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
        publish = re.search(r"(?ms)^  publish:\n(.*?)(?=^  [\w-]+:|\Z)", workflow.read_text())
        self.assertIsNotNone(publish, "Missing publish job")
        body = publish.group(1)
        self.assertRegex(body, r"(?m)^    needs:\n      - final-status$")

        # Read the job-level scalar and its continuation lines using the workflow's indentation.
        condition = re.search(r"(?m)^    if: (.+(?:\n      .+)*)", body)
        self.assertIsNotNone(condition, "Missing publish condition")
        expression = " ".join(condition.group(1).split())
        expression = expression.removeprefix(">- ").removeprefix("${{ ").removesuffix(" }}")
        self.assertCountEqual(
            [
                "!cancelled()",
                "needs.final-status.result == 'success'",
                "github.ref == 'refs/heads/main'",
                "github.repository == 'zacsweers/metro'",
            ],
            expression.split(" && "),
        )


if __name__ == "__main__":
    unittest.main()
