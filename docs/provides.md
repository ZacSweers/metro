# `@Provides`

Declare providers in a graph or its supertypes. Use a supertype to group providers and share them across graphs, similar to a Dagger module.

```kotlin
interface NetworkProviders {
  @Provides
  fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph
interface AppGraph : NetworkProviders
```

Provider functions can be `private`. Before Metro `0.10.0`, the compiler made them private automatically. That transformation was removed because it produced less efficient code. See the changelog.

Provider properties cannot be private because of [KT-76257](https://youtrack.jetbrains.com/issue/KT-76257/).

Providers can also be declared in [binding containers](dependency-graphs.md#binding-containers).

Provider functions can declare type parameters when [derived bindings](derived-bindings.md) are enabled.

!!! tip
    Avoid calling providers from other providers.

#### Overrides

Overriding a provider is a compile error. It’s tempting to bring back Dagger 1’s _module overrides_ for tests, but things get tricky when we generate the graph.

* What if the override returns a subtype or supertype?
* What if it needs different dependencies?

For tests, I’d use `DependencyGraph.excludes` and `ContributesTo.replaces` during contribution merging.

```kotlin
// Don't do this pattern!
interface NetworkProviders {
  @Provides
  fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph
interface TestAppGraph : NetworkProviders {
  // This will fail to compile
  override fun provideHttpClient(): HttpClient = TestHttpClient()
}
```

#### Companion Providers

Declare providers in a `companion object` to generate static calls.

```kotlin
interface MessageGraph {
  val message: String
  companion object {
    @Provides
    fun provideMessage(): String = "Hello, world!"
  }
}
```

??? note "Implementation Notes"

    Private interface functions are usually invisible to downstream compilations in IR. Metro uses a [Kotlin 2.1.20 API](https://github.com/JetBrains/kotlin/blob/b2bceb12ef57664c4f9b168157c3a097a81a6e5f/compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/extensions/IrGeneratedDeclarationsRegistrar.kt#L26) to record their locations in metadata on the parent class.
