# Multiplatform

The runtime and code gen have been implemented to be entirely platform-agnostic so far.

## Supported Targets for artifacts/features

| Artifact/feature             | JVM | Android |       JS        |      WASM       |      Apple      |      Linux      |     Windows     | Android Native  |
|------------------------------|:---:|---------|:---------------:|:---------------:|:---------------:|:---------------:|:---------------:|:---------------:|
| runtime                      |  ✅  | ✅       |        ✅        |        ✅        |        ✅        |        ✅        |        ✅        |        ✅        |
| interop-javax                |  ✅  | ✅       |        ―        |        ―        |        ―        |        ―        |        ―        |        ―        |
| interop-jakarta              |  ✅  | ✅       |        ―        |        ―        |        ―        |        ―        |        ―        |        ―        |
| interop-dagger               |  ✅  | ✅       |        ―        |        ―        |        ―        |        ―        |        ―        |        ―        |
| interop-guice                |  ✅  | ✅       |        ―        |        ―        |        ―        |        ―        |        ―        |        ―        |
| ---                          |  -  | -       |        -        |        -        |        -        |        -        |        -        |        -        |
| Multi-module aggregation     |  ✅  | ✅       | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` |
| Top-level function injection |  ✅  | ✅       | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` | Kotlin `2.3.20` |

**Legend:**
- **WASM**: wasmJs, wasmWasi
- **Apple**: macOS (x64, arm64), iOS (x64, arm64, simulatorArm64), watchOS (x64, arm32, arm64, deviceArm64, simulatorArm64), tvOS (x64, arm64, simulatorArm64)
- **Linux**: linuxX64, linuxArm64
- **Windows**: mingwX64
- **Android Native**: androidNativeArm32, androidNativeArm64, androidNativeX86, androidNativeX64

Cross-platform aggregation features are only supported on JVM and Android at the moment but should be available in Kotlin 2.3.20+. Follow [this issue](https://github.com/ZacSweers/metro/issues/460).

When mixing contributions between common and platform-specific source sets, you must define your final `@DependencyGraph` in the platform-specific code. This is because a graph defined in commonMain wouldn’t have full visibility of contributions from platform-specific types. A good pattern for this is to define your canonical graph in commonMain *without* a `@DependencyGraph` annotation and then a `{Platform}{Graph}` type in the platform source set that extends it and does have the `@DependencyGraph`. Metro automatically exposes bindings of the base graph type on the graph for any injections that need it.

```kotlin
// In commonMain
interface AppGraph {
  val httpClient: HttpClient
}

// In jvmMain
@DependencyGraph
interface JvmAppGraph : AppGraph {
  @Provides fun provideHttpClient(): HttpClient = HttpClient(Netty)
}

// In androidMain
@DependencyGraph
interface AndroidAppGraph : AppGraph {
  @Provides fun provideHttpClient(): HttpClient = HttpClient(OkHttp)
}
```
