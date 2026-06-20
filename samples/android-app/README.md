# Android Sample

This is an (extremely) simple Android sample app that demonstrates using Metro to constructor-inject `Activity`, `Fragment`, and `ViewModel` with multibindings.

## Runtime tracing

This sample also exercises Metro runtime tracing in a real Android graph. The tracing pieces are intentionally small:

- `build.gradle.kts` enables `metro { enableRuntimeTracing = true }`.
- `AppGraph.Factory` takes a `@Provides tracer: Tracer` input, because traced graph creation needs the AndroidX tracer before any generated binding code runs.
- `MetroApp` owns the `TraceDriver` and `TraceSink`, then passes `driver.tracer` into the generated graph factory.
- `AndroidManifest.xml` removes AndroidX's default profiler tracing initializer so profiler broadcasts use the same `TraceDriver` as the sample app.

Use the **Flush Traces** button to ask the app's `TraceDriver` to write pending trace data. You can also flush from adb:

```shell
adb shell am broadcast \
  -a androidx.tracing.profiler.action.FLUSH_TRACES_GET_PATH \
  dev.zacsweers.metro.sample.android/androidx.tracing.profiler.ConnectedProfilerTracingReceiver
```
