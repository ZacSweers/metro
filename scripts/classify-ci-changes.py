#!/usr/bin/env python3

# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Select PR checks from the complete Git diff; unknown paths retain full coverage."""

import argparse
import html
import json
import os
from pathlib import Path
import re
import subprocess
import sys


DOC_PATHS = frozenset(
    {
        "mkdocs.yml",
        ".github/workflows/mkdocs-requirements.txt",
        "scripts/generate_docs_dokka.sh",
        "scripts/copy_docs_files.sh",
    }
)
DOC_WORKFLOW_PATHS = frozenset(
    {".github/workflows/docs-validation.yml", ".github/workflows/docs-site.yml"}
)


def classify_paths(paths: list[str], event_name: str) -> dict[str, bool]:
    """Keep main/manual coverage and combine routes for every changed PR path."""
    if event_name != "pull_request":
        return {"full": True, "docs": False, "idea": True}

    full = not paths
    docs = False
    idea = False
    for path in paths:
        if path in DOC_WORKFLOW_PATHS:
            # Changes to documentation infrastructure also validate the full CI path.
            docs = True
            full = True
        elif path.endswith(".md") or path.startswith("docs/") or path in DOC_PATHS:
            docs = True
        elif path.startswith("idea-plugin/"):
            idea = True
        else:
            full = True
    return {"full": full, "docs": docs, "idea": full or idea}


def git(repository: Path, *arguments: str) -> bytes:
    """Run Git with separate arguments so filenames and input SHAs stay literal."""
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def changed_paths(repository: Path, base_sha: str, head_sha: str) -> list[str]:
    """Read both sides of renames and all changed paths from the PR merge base."""
    for sha in (base_sha, head_sha):
        if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", sha) is None:
            raise ValueError("PR base and head must be complete commit SHAs")

    # A shallow checkout can hide the common ancestor even when both tips exist.
    # Path classification needs commits and trees; historic file contents stay unfetched.
    if git(repository, "rev-parse", "--is-shallow-repository").strip() == b"true":
        git(
            repository,
            "fetch",
            "--no-tags",
            "--filter=blob:none",
            "--unshallow",
            "origin",
            base_sha,
            head_sha,
        )
    else:
        missing = []
        for sha in (base_sha, head_sha):
            try:
                git(repository, "cat-file", "-e", f"{sha}^{{commit}}")
            except subprocess.CalledProcessError:
                missing.append(sha)
        if missing:
            git(repository, "fetch", "--no-tags", "--filter=blob:none", "origin", *missing)

    bases = git(repository, "merge-base", "--all", base_sha, head_sha).splitlines()
    if len(bases) != 1:
        raise ValueError("PR history must have exactly one merge base")

    # Disabling rename detection yields the old deletion and new addition separately.
    # NUL delimiters preserve paths containing spaces, tabs, and newlines.
    output = git(
        repository,
        "diff",
        "--name-only",
        "--no-renames",
        "-z",
        bases[0].decode("ascii"),
        head_sha,
        "--",
    )
    return [os.fsdecode(path) for path in output.split(b"\0") if path]


def summary_code(value: str) -> str:
    """Keep arbitrary filenames printable and literal inside the Markdown summary."""
    display = json.dumps(value, ensure_ascii=True)[1:-1]
    if len(display) > 200:
        display = display[:197] + "..."
    escaped = html.escape(display)
    for character in "`*_[]\\":
        escaped = escaped.replace(character, f"&#{ord(character)};")
    return f"<code>{escaped}</code>"


def render_summary(paths: list[str], event_name: str, routes: dict[str, bool]) -> str:
    """Explain the existing routing result with bounded examples from each path group."""
    lines = [
        "## CI change classification",
        "",
        f"Event: {summary_code(event_name)}",
        "",
        f"Changed paths examined: {len(paths)}",
        "",
        "| Route | Decision |",
        "| --- | --- |",
    ]
    for name, enabled in routes.items():
        decision = "Selected" if enabled else "Skipped"
        lines.append(f"| {name} | {decision} |")
    lines.extend(["", "### Reasons", ""])

    if event_name != "pull_request":
        lines.append(
            "This event keeps full CI and IDEA plugin tests. No PR diff was inspected. "
            "Documentation publishing uses its separate workflow."
        )
    elif not paths:
        lines.append(
            "The PR diff contains no changed paths. Full CI and IDEA plugin tests "
            "remain selected as a conservative fallback."
        )
    else:
        reasons = {
            (True, False, True): "Other changed paths retain full CI and IDEA plugin tests",
            (True, True, True): "Documentation workflows select docs validation and retain full CI",
            (False, True, False): "Documentation paths select docs validation",
            (False, False, True): "IDEA plugin paths select IDEA plugin tests",
        }
        examples = {key: [] for key in reasons}
        counts = {key: 0 for key in reasons}
        for path in paths:
            # Derive each explanation from the same rules that selected the jobs.
            route = classify_paths([path], event_name)
            key = (route["full"], route["docs"], route["idea"])
            counts[key] += 1
            if len(examples[key]) < 5:
                examples[key].append(path)
        for key, reason in reasons.items():
            count = counts[key]
            if count == 0:
                continue
            noun = "path" if count == 1 else "paths"
            lines.extend([f"{reason} ({count} changed {noun}).", ""])
            lines.extend(f"- {summary_code(path)}" for path in examples[key])
            omitted = count - len(examples[key])
            if omitted:
                lines.append(f"- {omitted} additional paths omitted.")
            lines.append("")
    return "\n".join(lines) + "\n"


def write_summary(paths: list[str], event_name: str, routes: dict[str, bool]) -> None:
    """Keep optional summary I/O failures separate from the required routing outputs."""
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    try:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(render_summary(paths, event_name, routes))
    except OSError as error:
        print(f"Warning: Could not write CI classification summary: {error}", file=sys.stderr)


def main() -> int:
    """Print boolean action outputs; Git failures fail the required classifier job."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    arguments = parser.parse_args()

    paths = []
    if arguments.event_name == "pull_request":
        try:
            paths = changed_paths(Path.cwd(), arguments.base_sha, arguments.head_sha)
        except subprocess.CalledProcessError as error:
            print(
                f"Could not classify PR changes: git {error.cmd[1]} failed "
                f"with exit code {error.returncode}",
                file=sys.stderr,
            )
            return 1
        except (OSError, ValueError) as error:
            print(f"Could not classify PR changes: {error}", file=sys.stderr)
            return 1

    routes = classify_paths(paths, arguments.event_name)
    for name, enabled in routes.items():
        print(f"{name}={str(enabled).lower()}")
    write_summary(paths, arguments.event_name, routes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
