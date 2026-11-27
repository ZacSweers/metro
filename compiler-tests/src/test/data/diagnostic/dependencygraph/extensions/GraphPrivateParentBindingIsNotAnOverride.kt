// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// The child never inherits a @GraphPrivate parent binding, so declaring the same key isn't an
// override.

class Foo(val value: String)

// Parent accessors can't expose @GraphPrivate bindings, so the parent uses them through this.
@Inject class ParentConsumer(val string: String, val foo: Foo)

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val consumer: ParentConsumer

  @GraphPrivate @Provides fun provideString(): String = "parent"

  @GraphPrivate @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("parent")

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String
  val foo: Foo

  @Provides fun provideString(): String = "child"

  @Provides fun provideFoo(): Foo = Foo("child")

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
