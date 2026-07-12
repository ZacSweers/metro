# Compiler/IDE graph parity fixtures

These fixtures are compiled by the Metro compiler tests and loaded as source by
`MetroGraphValidationParityTest`. The checked-in graph metadata, key reports, and rendered Metro
diagnostics define the expected compiler behavior for IDEA graph validation. Report goldens live
under `_reports/<fixture name>` so generated test discovery ignores them.

Graph-metadata fixtures use `NORMALIZE_REPORT_SOURCE_LOCATIONS` because source locations are outside
the parity contract and generated IR offsets vary across supported Kotlin compiler versions.

Regenerate compiler goldens after an intentional compiler behavior change:

```shell
./gradlew :compiler-tests:test \
  --tests 'dev.zacsweers.metro.compiler.DiagnosticTestGenerated$IdeaParity' \
  -PupdateTestData=true --quiet
```

`BinaryGraph.kt` mirrors the declarations in the IDEA plugin's compiled `libFixture`. Keep the two
fixtures aligned when adding binary-resolution coverage.
