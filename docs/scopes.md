# Scopes

A scope caches a binding’s instance in a graph. Define a scope with an annotation marked `@Scope`, or use Metro’s `@SingleIn` annotation.

Apply the scope to the injected class or its provider function. The graph must support the same scope.

```kotlin
@SingleIn(AppScope::class)
@DependencyGraph
abstract class AppGraph {
  private var counter = 0

  abstract val count: Int

  @SingleIn(AppScope::class) @Provides fun provideCount() = counter++
}
```

The first call to `AppGraph.count` caches the result of `provideCount()`. Every call on that graph instance returns `0`.

It is a compile error for an unscoped graph to access a scoped binding.

```kotlin
@DependencyGraph
interface AppGraph {
  // This is an error!
  val exampleClass: ExampleClass
}

@SingleIn(AppScope::class)
@Inject
class ExampleClass
```

It is a compile error for a scoped graph to access a binding whose scope the graph does not support.

```kotlin
@SingleIn(AppScope::class)
@DependencyGraph
interface AppGraph {
  // This is an error!
  val exampleClass: ExampleClass
}

@SingleIn(UserScope::class)
@Inject
class ExampleClass
```

A graph can support multiple scopes.

```kotlin
@Scope annotation class Singleton

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
