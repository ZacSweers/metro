#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Check every selected CI job and allow only skips requested by the change classifier."""

import argparse
import json
import os
import sys


def check_results(needs, workflow, is_main=False):
    """Return failures for missing jobs, failed jobs, and unexpected dependency skips."""
    changes = needs.get("changes", {})
    if changes.get("result") != "success":
        return ["Change classification did not succeed"]

    routes = changes.get("outputs", {})
    if any(routes.get(name) not in ("true", "false") for name in ("full", "docs", "idea")):
        return ["Change classification returned invalid routes"]

    required = {"changes"}
    if workflow == "ci":
        required.add("format")
        if routes["full"] == "true":
            required.update(("generate-matrix", "core", "compatibility", "shaded-compiler-smoke",
                             "js-box", "benchmarks", "samples"))
        if routes["idea"] == "true":
            required.add("idea-plugin")
        if routes["docs"] == "true":
            required.add("docs")
        if is_main:
            required.add("kmp-functional")
    elif workflow == "ide":
        if routes["full"] == "true":
            required.update(("generate-matrix", "build-metro", "ide-tests"))
    else:
        raise ValueError(f"Unknown workflow: {workflow}")

    errors = []
    for name in sorted(required | needs.keys()):
        result = needs.get(name, {}).get("result")
        allowed = ("success",) if name in required else ("success", "skipped")
        if result not in allowed:
            errors.append(f"{name}: {result or 'missing'}")
    return errors


def main():
    """Read GitHub's complete needs object and fail the aggregate when coverage is missing."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workflow", choices=("ci", "ide"))
    args = parser.parse_args()
    errors = check_results(json.loads(os.environ["NEEDS_JSON"]), args.workflow,
                          os.environ.get("GITHUB_REF") == "refs/heads/main")
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
