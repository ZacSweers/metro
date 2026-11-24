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
    - watchOS (`arm64`, `deviceArm64`, `simulatorArm64`)
    - tvOS (`arm64`, `simulatorArm64`)
- **Linux**:
    - `linuxX64`
    - `linuxArm64`
- **Wasm**:
    - `wasmJs`
    - `wasmWasi`
- **Windows**:
    - `mingwX64`

If you combine common and platform-specific contributions, the final `@DependencyGraph` must be defined in the platform source set. A graph in `commonMain` cannot see platform-specific contributions. Define an unannotated graph interface in `commonMain`, then extend it with an annotated `{Platform}{Graph}` in each platform source set. Metro also exposes a binding for the base graph type.

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
