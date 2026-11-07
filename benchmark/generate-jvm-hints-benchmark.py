#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0
"""Generate a fixed-source JVM workload for comparing FIR and IR contribution hints."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--count", type=int, default=500)
    parser.add_argument("--warm-ups", type=int, default=5)
    parser.add_argument("--same-module", action="store_true")
    parser.add_argument("--kotlin-version", default="2.4.21")
    parser.add_argument("--metro-version", default="1.6.0-SNAPSHOT")
    args = parser.parse_args()
    if args.count < 0:
        parser.error("--count must be nonnegative")
    if args.warm_ups < 1:
        parser.error("--warm-ups must be positive")
    if args.output.exists():
        parser.error("output already exists; choose a new directory")
    repo = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True)

    def write(name: str, content: str) -> None:
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    shutil.copy2(repo / "gradlew", output / "gradlew")
    shutil.copytree(repo / "gradle/wrapper", output / "gradle/wrapper")
    repository = (repo / "build/functionalTestRepo").as_uri()
    write(
        "settings.gradle.kts",
        f'''pluginManagement {{
  repositories {{ maven(url = "{repository}"); gradlePluginPortal(); mavenCentral() }}
}}
dependencyResolutionManagement {{
  repositories {{ maven(url = "{repository}"); mavenCentral() }}
}}
rootProject.name = "jvm-hints-benchmark"
include(":producer", ":consumer")
''',
    )
    write(
        "build.gradle.kts",
        f'''plugins {{
  kotlin("jvm") version "{args.kotlin_version}" apply false
  id("dev.zacsweers.metro") version "{args.metro_version}" apply false
}}
''',
    )
    write(
        "gradle.properties",
        "org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC\n"
        "org.gradle.workers.max=4\norg.gradle.caching=false\n"
        "org.gradle.configuration-cache=false\n"
        "kotlin.compiler.execution.strategy=in-process\n",
    )
    module_build = '''plugins { kotlin("jvm"); id("dev.zacsweers.metro") }
kotlin { jvmToolchain(25) }
metro {
  compilerOptions.put("generate-jvm-contribution-hints-in-ir", providers.gradleProperty("irHints"))
}
'''
    write("producer/build.gradle.kts", module_build)
    write(
        "consumer/build.gradle.kts",
        module_build
        + '''apply(plugin = "application")
dependencies { implementation(project(":producer")) }
configure<JavaApplication> { mainClass.set("bench.GraphKt") }
''',
    )
    producer = "producer/src/main/kotlin/bench/"
    consumer = "consumer/src/main/kotlin/bench/"
    contributions = consumer if args.same_module else producer
    write(producer + "Contracts.kt", "package bench\nabstract class BenchmarkScope\ninterface Value { fun value(): Int }\n")
    write(contributions + "Plain.kt", "package bench\nclass Plain { fun value(): Int { return 1 } }\n")
    for index in range(args.count):
        write(
            contributions + f"Binding{index}.kt",
            "package bench\nimport dev.zacsweers.metro.*\n"
            "@ContributesIntoSet(BenchmarkScope::class)\n"
            f"@Inject class Binding{index} : Value {{ override fun value(): Int {{ return {index} }} }}\n",
        )
    empty_set = "@Multibinds(allowEmpty = true) " if args.count == 0 else ""
    write(
        consumer + "Graph.kt",
        "package bench\nimport dev.zacsweers.metro.*\n"
        f"@DependencyGraph(BenchmarkScope::class) interface Graph {{ {empty_set}val values: Set<Value> }}\n"
        f"fun main() {{ check(createGraph<Graph>().values.size == {args.count}) }}\n",
    )
    scenarios = []
    mutations = {
        "unrelated_body": f'apply-non-abi-change-to = "{contributions}Plain.kt"',
        "unrelated_abi": f'apply-abi-change-to = "{contributions}Plain.kt"',
        "contribution_body": f'apply-non-abi-change-to = "{contributions}Binding0.kt"',
        "graph_body": f'apply-non-abi-change-to = "{consumer}Graph.kt"',
        "noop": "",
        "clean": 'cleanup-tasks = ["clean"]',
    }
    if args.count == 0:
        del mutations["contribution_body"]
    for mode, enabled in (("fir", "false"), ("ir", "true")):
        for mutation, setting in mutations.items():
            scenarios.append(f'''{mode}_{mutation} {{
  title = "{mode}_{mutation}"
  warm-ups = {args.warm_ups}
  iterations = 10
  tasks = [":consumer:compileKotlin"]
  gradle-args = ["-PirHints={enabled}"]
  {setting}
}}
''')
    write("hints.scenarios", "\n".join(scenarios))
    source_hash = hashlib.sha256()
    for path in sorted(output.rglob("*.kt")):
        source_hash.update(path.relative_to(output).as_posix().encode())
        source_hash.update(path.read_bytes())
    write(
        "workload.json",
        json.dumps(
            {
                "contributions": args.count,
                "sameModule": args.same_module,
                "kotlin": args.kotlin_version,
                "metro": args.metro_version,
                "sourceSha256": source_hash.hexdigest(),
            },
            indent=2,
        ) + "\n",
    )
    print(output)


if __name__ == "__main__":
    main()
