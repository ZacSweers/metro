#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Check that resource monitoring releases output when Gradle exits."""

import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest


class ResourceMonitorTest(unittest.TestCase):
    def run_gradle(self, exit_code):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "run-ci-gradle.sh"
            shutil.copy2(Path(__file__).with_name("run-ci-gradle.sh"), wrapper)
            gradle = root / "gradlew"
            gradle.write_text(f"#!/bin/sh\nsleep 1\necho 'Gradle finished'\nexit {exit_code}\n")
            gradle.chmod(0o755)
            environment = os.environ.copy()
            environment["RUNNER_DEBUG"] = "1"
            environment["GITHUB_WORKSPACE"] = directory
            process = subprocess.Popen(
                [str(wrapper)],
                cwd=root,
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
            try:
                output, _ = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                output, _ = process.communicate()
                self.fail(f"Resource monitoring kept the output pipe open after Gradle exited:\n{output}")
            self.assertEqual(exit_code, process.returncode, output)
            self.assertIn("CI resources:", output)
            self.assertIn("Gradle finished", output)

    def test_success_releases_output(self):
        self.run_gradle(0)

    def test_failure_releases_output_and_preserves_exit_code(self):
        self.run_gradle(7)


if __name__ == "__main__":
    unittest.main()
