# Multiplatform

Should Just Work™️ but will see when we get there\! The runtime and code gen have been implemented to be entirely platform-agnostic so far.

There is one issue in the repo right now where the compiler appears to have a bug with generated FIR declarations where it doesn’t deserialize them correctly on non-JVM targets. Waiting for feedback from JB.

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
