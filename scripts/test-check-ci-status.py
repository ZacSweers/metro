#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Ensure required CI results include every selected compiler and platform job."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "check_ci_status", Path(__file__).with_name("check-ci-status.py")
)
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class RequiredResultsTest(unittest.TestCase):
    def results(self):
        names = (
            "changes", "format", "generate-matrix", "core", "compatibility",
            "functional-compatibility", "shaded-compiler-smoke", "js-box",
            "benchmarks", "samples", "idea-plugin", "idea-plugin-compatibility", "multiplatform-tests",
        )
        results = {name: {"result": "success"} for name in names}
        results["changes"]["outputs"] = {"full": "true", "docs": "false", "idea": "true"}
        return results

    def test_platform_result_is_required_for_full_platform_runs(self):
        for result in (None, "skipped", "failure", "cancelled"):
            with self.subTest(result=result):
                results = self.results()
                if result is None:
                    del results["multiplatform-tests"]
                else:
                    results["multiplatform-tests"]["result"] = result
                errors = checker.check_results(results, "ci", full_platforms=True)
                self.assertTrue(any(error.startswith("multiplatform-tests:") for error in errors))

    def test_compiler_and_functional_phases_are_both_required(self):
        for name in ("compatibility", "functional-compatibility"):
            with self.subTest(name=name):
                results = self.results()
                del results[name]
                self.assertIn(f"{name}: missing", checker.check_results(results, "ci"))

    def test_pull_request_can_skip_main_only_platform_jobs(self):
        results = self.results()
        results["multiplatform-tests"]["result"] = "skipped"
        self.assertEqual([], checker.check_results(results, "ci"))

    def test_idea_verification_is_required(self):
        results = self.results()
        del results["idea-plugin-compatibility"]
        self.assertIn("idea-plugin-compatibility: missing", checker.check_results(results, "ci"))

    def test_full_platform_run_passes_when_all_jobs_succeed(self):
        self.assertEqual([], checker.check_results(self.results(), "ci", full_platforms=True))

    def test_idea_only_change_can_skip_compiler_jobs(self):
        results = {
            "changes": {
                "result": "success",
                "outputs": {"full": "false", "docs": "false", "idea": "true"},
            },
            "format": {"result": "success"},
            "idea-plugin": {"result": "success"},
            "idea-plugin-compatibility": {"result": "success"},
            "compatibility": {"result": "skipped"},
            "functional-compatibility": {"result": "skipped"},
            "multiplatform-tests": {"result": "skipped"},
        }
        self.assertEqual([], checker.check_results(results, "ci"))


class RequiredIdeResultsTest(unittest.TestCase):
    def results(self):
        names = ("changes", "generate-matrix", "build-metro", "build-idea-plugin", "ide-tests")
        results = {name: {"result": "success"} for name in names}
        results["changes"]["outputs"] = {"full": "false", "docs": "false", "idea": "true"}
        return results

    def test_both_artifact_producers_are_required(self):
        for name in ("build-metro", "build-idea-plugin"):
            for result in (None, "skipped", "failure", "cancelled"):
                with self.subTest(name=name, result=result):
                    results = self.results()
                    if result is None:
                        del results[name]
                    else:
                        results[name]["result"] = result
                    self.assertIn(f"{name}: {result or 'missing'}", checker.check_results(results, "ide"))

    def test_ide_run_passes_when_all_jobs_succeed(self):
        self.assertEqual([], checker.check_results(self.results(), "ide"))

    def test_unselected_ide_run_can_skip_artifact_producers(self):
        results = self.results()
        results["changes"]["outputs"]["idea"] = "false"
        for name in ("generate-matrix", "build-metro", "build-idea-plugin", "ide-tests"):
            results[name]["result"] = "skipped"
        self.assertEqual([], checker.check_results(results, "ide"))


if __name__ == "__main__":
    unittest.main()
