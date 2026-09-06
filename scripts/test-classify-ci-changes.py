#!/usr/bin/env python3

# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Exercise CI routing and complete diff discovery in temporary Git repositories."""

import html
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "classify_ci_changes", Path(__file__).with_name("classify-ci-changes.py")
)
classifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(classifier)


class ClassifyPathsTest(unittest.TestCase):
    """Protect the small skip allowlist and the union of routes for mixed changes."""

    def test_routes(self):
        cases = [
            (["README.md"], {"full": False, "docs": True, "idea": False}),
            (["idea-plugin/src/Graph.kt"], {"full": False, "docs": False, "idea": True}),
            (
                ["README.md", "idea-plugin/src/Graph.kt"],
                {"full": False, "docs": True, "idea": True},
            ),
            (["compiler/src/Graph.kt"], {"full": True, "docs": False, "idea": True}),
            (
                ["README.md", "compiler/src/Graph.kt"],
                {"full": True, "docs": True, "idea": True},
            ),
            (["metro-common/src/Graph.kt"], {"full": True, "docs": False, "idea": True}),
            (["scripts/unknown.py"], {"full": True, "docs": False, "idea": True}),
            ([], {"full": True, "docs": False, "idea": True}),
        ]
        for paths, expected in cases:
            with self.subTest(paths=paths):
                self.assertEqual(expected, classifier.classify_paths(paths, "pull_request"))

    def test_docs_inputs(self):
        paths = [
            "docs/site-assets/image.png",
            "mkdocs.yml",
            ".github/workflows/mkdocs-requirements.txt",
            "scripts/generate_docs_dokka.sh",
            "scripts/copy_docs_files.sh",
            "idea-plugin/README.md",
        ]
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(
                    {"full": False, "docs": True, "idea": False},
                    classifier.classify_paths([path], "pull_request"),
                )

    def test_non_pr_keeps_full_coverage(self):
        for event in ("push", "workflow_dispatch", "merge_group"):
            with self.subTest(event=event):
                self.assertEqual(
                    {"full": True, "docs": False, "idea": True},
                    classifier.classify_paths(["README.md"], event),
                )

    def test_docs_workflows_keep_full_coverage(self):
        for path in (".github/workflows/docs-validation.yml", ".github/workflows/docs-site.yml"):
            with self.subTest(path=path):
                self.assertEqual(
                    {"full": True, "docs": True, "idea": True},
                    classifier.classify_paths([path], "pull_request"),
                )


class ChangedPathsTest(unittest.TestCase):
    """Use real Git history to catch rename, filename, and shallow-checkout mistakes."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.git("init", "--initial-branch=main")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI test")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", "/dev/null")

    def git(self, *arguments):
        """Run commands only in the disposable test repository."""
        return classifier.git(self.repository, *arguments)

    def write(self, name, content="content\n"):
        """Create a fixture file, preserving unusual characters in its path."""
        path = self.repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self):
        """Commit fixture changes and return their exact object ID."""
        self.git("add", "--all")
        self.git("commit", "--message", "fixture")
        return self.git("rev-parse", "HEAD").decode().strip()

    def test_renames_keep_old_and_new_paths(self):
        self.write("compiler/Graph.kt")
        base = self.commit()
        self.git("mv", "compiler/Graph.kt", "README.md")
        head = self.commit()
        paths = classifier.changed_paths(self.repository, base, head)
        self.assertCountEqual(["compiler/Graph.kt", "README.md"], paths)
        self.assertEqual(
            {"full": True, "docs": True, "idea": True},
            classifier.classify_paths(paths, "pull_request"),
        )

    def test_deleted_files_and_newlines(self):
        self.write("compiler/removed.kt")
        base = self.commit()
        (self.repository / "compiler/removed.kt").unlink()
        self.write("docs/with\na newline.md")
        self.write("idea-plugin/with\tspaces and tabs.kt")
        head = self.commit()
        self.assertCountEqual(
            ["compiler/removed.kt", "docs/with\na newline.md", "idea-plugin/with\tspaces and tabs.kt"],
            classifier.changed_paths(self.repository, base, head),
        )

    def test_uses_merge_base(self):
        self.write("README.md")
        self.commit()
        self.git("checkout", "-b", "pull-request")
        self.write("docs/change.md")
        head = self.commit()
        self.git("checkout", "main")
        self.write("compiler/main-only.kt")
        base = self.commit()
        self.assertEqual(
            ["docs/change.md"], classifier.changed_paths(self.repository, base, head)
        )

    def test_shallow_checkout_fetches_history(self):
        self.write("README.md")
        base = self.commit()
        historic_blob = self.git("rev-parse", f"{base}:README.md").strip()
        self.write("README.md", "updated\n")
        self.write("docs/change.md", "new documentation\n")
        head = self.commit()
        self.git("config", "uploadpack.allowFilter", "true")
        checkout = self.root / "checkout"
        self.git("clone", "--depth=1", "--no-local", str(self.repository), str(checkout))
        self.assertCountEqual(
            ["README.md", "docs/change.md"], classifier.changed_paths(checkout, base, head)
        )
        self.assertEqual(
            b"false\n", classifier.git(checkout, "rev-parse", "--is-shallow-repository")
        )
        missing_objects = classifier.git(checkout, "rev-list", "--objects", "--all", "--missing=print")
        self.assertIn(b"?" + historic_blob, missing_objects.splitlines())

    def test_invalid_sha_is_rejected(self):
        with self.assertRaises(ValueError):
            classifier.changed_paths(self.repository, "--upload-pack=unexpected", "a" * 40)

    def test_missing_commit_fetch_failure_is_reported(self):
        self.write("README.md")
        base = self.commit()
        with self.assertRaises(subprocess.CalledProcessError):
            classifier.changed_paths(self.repository, base, "a" * 40)


class SummaryTest(unittest.TestCase):
    """Keep routing explanations readable without altering machine-readable outputs."""

    def test_mixed_paths_explain_each_selected_route(self):
        paths = ["README.md", "idea-plugin/src/Graph.kt", "compiler/src/Graph.kt"]
        summary = classifier.render_summary(
            paths, "pull_request", classifier.classify_paths(paths, "pull_request")
        )
        self.assertIn("Event: <code>pull_request</code>", html.unescape(summary))
        self.assertIn("Changed paths examined: 3", summary)
        for route in ("full", "docs", "idea"):
            self.assertIn(f"| {route} | Selected |", summary)
        self.assertIn("Other changed paths retain full CI", summary)
        self.assertIn("Documentation paths select docs validation", summary)
        self.assertIn("IDEA plugin paths select IDEA plugin tests", summary)
        for path in paths:
            self.assertIn(path, summary)

    def test_docs_summary_shows_skipped_routes(self):
        paths = ["docs/guide.md"]
        summary = classifier.render_summary(
            paths, "pull_request", classifier.classify_paths(paths, "pull_request")
        )
        self.assertIn("| full | Skipped |", summary)
        self.assertIn("| docs | Selected |", summary)
        self.assertIn("| idea | Skipped |", summary)

    def test_docs_workflow_summary_explains_full_coverage(self):
        paths = [".github/workflows/docs-validation.yml"]
        summary = classifier.render_summary(
            paths, "pull_request", classifier.classify_paths(paths, "pull_request")
        )
        self.assertIn("Documentation workflows select docs validation and retain full CI", summary)
        self.assertIn("(1 changed path)", summary)
        for route in ("full", "docs", "idea"):
            self.assertIn(f"| {route} | Selected |", summary)

    def test_non_pr_and_empty_diff_have_distinct_explanations(self):
        for event, explanation in (
            ("push", "No PR diff was inspected"),
            ("pull_request", "The PR diff contains no changed paths"),
        ):
            with self.subTest(event=event):
                summary = classifier.render_summary([], event, classifier.classify_paths([], event))
                self.assertIn("Changed paths examined: 0", summary)
                self.assertIn(explanation, summary)
                self.assertIn("| full | Selected |", summary)

    def test_path_examples_escape_markup_and_control_characters(self):
        path = "docs/<img src=x>`[link](https://example.invalid)\n\t\udcff.md"
        summary = classifier.render_summary(
            [path], "pull_request", classifier.classify_paths([path], "pull_request")
        )
        self.assertNotIn("<img", summary)
        self.assertNotIn("`[link]", summary)
        self.assertIn("&lt;img src=x&gt;&#96;&#91;link&#93;", summary)
        self.assertIn("&#92;n&#92;t&#92;udcff.md", summary)
        self.assertEqual(1, summary.count("- <code>"))
        summary.encode("utf-8")

    def test_path_examples_are_bounded(self):
        paths = [f"docs/{index}-" + "x" * 300 + ".md" for index in range(12)]
        summary = classifier.render_summary(
            paths, "pull_request", classifier.classify_paths(paths, "pull_request")
        )
        self.assertIn("Changed paths examined: 12", summary)
        self.assertEqual(5, summary.count("- <code>"))
        self.assertIn("7 additional paths omitted", summary)
        self.assertNotIn("x" * 201, summary)

    def test_cli_keeps_summary_separate_from_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            summary_path = Path(directory) / "summary.md"
            result = self.run_cli(summary_path)
            self.assertEqual(0, result.returncode)
            self.assertEqual("full=true\ndocs=false\nidea=true\n", result.stdout)
            self.assertEqual("", result.stderr)
            self.assertIn("## CI change classification", summary_path.read_text())

    def test_summary_write_failure_preserves_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_cli(Path(directory))
            self.assertEqual(0, result.returncode)
            self.assertEqual("full=true\ndocs=false\nidea=true\n", result.stdout)
            self.assertIn("Could not write CI classification summary", result.stderr)

    def run_cli(self, summary_path):
        """Exercise the script with an isolated summary destination and real stdout."""
        environment = os.environ.copy()
        environment["GITHUB_STEP_SUMMARY"] = str(summary_path)
        return subprocess.run(
            [sys.executable, str(Path(__file__).with_name("classify-ci-changes.py")),
             "--event-name", "push"],
            env=environment,
            text=True,
            capture_output=True,
        )


if __name__ == "__main__":
    unittest.main()
