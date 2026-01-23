# `@Provides`

Providers can be defined in graphs or supertypes that graphs extend. Defining them in supertypes allows for them to be reused across multiple graphs and organize providers into logic groups. This is similar to how modules in Dagger work.

```kotlin
interface NetworkProviders {
  @Provides
  fun provideHttpClient(): HttpClient = HttpClient()
}

@DependencyGraph
interface AppGraph : NetworkProviders
```

Provider _functions_ can be `private` but, as of `0.10.0`, are no longer _automatically_ transformed to be private by the Metro compiler. Prior to `0.10.0`, they were. However, this resulted in less efficient generated code so it was disabled. See the CHANGELOG for more details.

Provider _properties_ cannot be private yet due to [KT-76257](https://youtrack.jetbrains.com/issue/KT-76257/), but may be supported in the future.

Providers may also be declared in [binding Containers](dependency-graphs.md#binding-containers).

!!! tip
    It’s recommended to *not* call providers from each other.

#### Overrides

It is an error to override providers declarations. While it can be enticing for testing reasons to try to replicate Dagger 1’s *module overrides*, it quickly becomes difficult to reason about in code gen.

* What if you override with sub/supertypes?
* What if your override’s implementation needs different dependencies?

To the testing end, it is recommended to instead leverage the `DependencyGraph.excludes` + `ContributesTo.replaces` APIs in merging.

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

Providers can alternatively be implemented in `companion object`s for staticization.

```kotlin
interface MessageGraph {
  val message: String
  companion object {
    @Provides
    private fun provideMessage(): String = "Hello, world!"
  }
}
```

??? note "Implementation Notes"

    private interface functions are not usually visible to downstream compilations in IR. To work around this, Metro will use a [new API in Kotlin 2.1.20](https://github.com/JetBrains/kotlin/blob/b2bceb12ef57664c4f9b168157c3a097a81a6e5f/compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/extensions/IrGeneratedDeclarationsRegistrar.kt#L26) to add custom metadata to the parent class to denote these private providers’ existence and where to find them.
