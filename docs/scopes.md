# Scopes

Like Dagger and KI, Metro supports *scopes* to limit instances of types on the dependency graph. A scope is any annotation annotated with `@Scope`, with a convenience `@Singleton` scope available in Metro’s runtime.

Scopes must be applied to either the injected class or the provider function providing that binding. They must also match the graph that they are used in.

```kotlin
@Singleton
@DependencyGraph
abstract class AppGraph {
  private var counter = 0

  abstract val count: Int

  @Singleton @Provides fun provideCount() = counter++
}
```

In the above example, multiple calls to `AppGraph.count` will always return 0 because the returned value from `provideCount()` will be cached in the `AppGraph` instance the first time it’s called.

It is an error for an unscoped graph to access scoped bindings.

```kotlin
@DependencyGraph
interface AppGraph {
  // This is an error!
  val exampleClass: ExampleClass
}

@Singleton
@Inject
class ExampleClass
```

It is also an error for a scoped graph to access scoped bindings whose scope does not match.

```kotlin
@Scope annotation class UserScope

@Singleton
@DependencyGraph
interface AppGraph {
  // This is an error!
  val exampleClass: ExampleClass
}

@UserScope
@Inject
class ExampleClass
```

Like Dagger, graphs can have multiple scopes that they support.

```kotlin
@Scope annotation class SingleIn(val scope: KClass<*>)

@Singleton
@SingleIn(AppScope::class)
@DependencyGraph
interface AppGraph {
  // This is ok
  val exampleClass: ExampleClass
}

@SingleIn(AppScope::class)
@Inject
class ExampleClass
```