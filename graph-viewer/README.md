# Graph viewer

This module converts Metro JSON reports into viewer data on JVM and JavaScript. The Gradle plugin uses `GraphReportRenderer` when generating HTML reports. The hosted viewer uses the same converter through `GraphReportImport`.

The browser reads selected files locally. `GraphReportSession` runs in a worker and validates each import before rendering. Each graph opens in a fresh sandboxed frame. Replacing a report releases the previous worker and frame.

The HTML, CSS, and JavaScript for the graph remain in the Gradle plugin's resources. `src/host/` contains the import page, its worker, and a sample report. `prepareGraphViewer` combines those files with the compiled JavaScript converter under `docs/graph-viewer/`.

## Development

```shell
./gradlew prepareGraphViewer --quiet
python3 -m http.server 8766 --bind 127.0.0.1 --directory docs
```

Open `http://127.0.0.1:8766/graph-viewer/index.html`. The docs build also calls `prepareGraphViewer` through `scripts/copy_docs_files.sh`.

Run the shared import and conversion tests on both targets:

```shell
./gradlew :graph-viewer:jvmTest :graph-viewer:jsNodeTest --quiet
```

The Gradle plugin's `GraphHtmlRendererTest` covers the complete conversion and HTML packaging. Its tests continue to use the shared converter.

## Report format

The importer accepts current unversioned compiler reports and aggregated metadata. It rejects unsupported explicit `formatVersion` values. Version 1 represents the current structure. Files with the same graph name must contain identical definitions.

Analysis remains optional. The importer checks graph names, binding keys, dependency counts, recorded connections, and longest-chain edges against the metadata. Analysis for graphs outside the import is skipped with a warning. Centrality, dominator counts, and longest-chain data come from `analysis.json`. The browser doesn't run the JVM analyzer.
