# Multiplatform

Metro's runtime and generated code support the platforms listed below.

## Supported Targets for artifacts/features

| Artifact/feature             |          JVM          | Android                |           JS           |          Wasm          |         Apple          |         Linux          |        Windows         |     Android Native     |
|------------------------------|:---------------------:|------------------------|:----------------------:|:----------------------:|:----------------------:|:----------------------:|:----------------------:|:----------------------:|
| runtime                      |           ✅           | ✅                      |           ✅            |           ✅            |           ✅            |           ✅            |           ✅            |           ✅            |
| runtime-coroutines           |           ✅           | ✅                      |           ✅            |           ✅            |           ✅            |           ✅            |           ✅            |           ✅            |
| interop-javax                |           ✅           | ✅                      |           ―            |           ―            |           ―            |           ―            |           ―            |           ―            |
| interop-jakarta              |           ✅           | ✅                      |           ―            |           ―            |           ―            |           ―            |           ―            |           ―            |
| interop-dagger               |           ✅           | ✅                      |           ―            |           ―            |           ―            |           ―            |           ―            |           ―            |
| interop-guice                |           ✅           | ✅                      |           ―            |           ―            |           ―            |           ―            |           ―            |           ―            |
| ---                          |           -           | -                      |           -            |           -            |           -            |           -            |           -            |           -            |
| Multi-module aggregation     | ✅<br/>Kotlin `2.3.0`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.21`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ |
| Top-level function injection | ✅<br/>Kotlin `2.3.0`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.21`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ | ✅<br/>Kotlin `2.3.20`+ |

`runtime-coroutines` uses kotlinx-coroutines on every platform.

**Legend:**

- **Android Native**:
    - `androidNativeArm32`
    - `androidNativeArm64`
    - `androidNativeX86`
    - `androidNativeX64`
- **Apple**:
    - macOS (`arm64`)
    - iOS (`x64`, `arm64`, `simulatorArm64`)
    - watchOS (`arm32`, `arm64`, `deviceArm64`, `simulatorArm64`)
    - tvOS (`arm64`, `simulatorArm64`)
- **Linux**:
    - `linuxX64`
    - `linuxArm64`
- **Wasm**:
    - `wasmJs`
    - `wasmWasi`
- **Windows**:
    - `mingwX64`

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
