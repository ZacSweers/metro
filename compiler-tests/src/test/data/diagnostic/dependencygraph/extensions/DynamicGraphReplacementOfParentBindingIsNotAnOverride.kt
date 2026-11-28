// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A dynamic graph replaces a scoped and an unscoped parent binding. The child only reads them, so
// nothing is overridden.

class Foo(val value: String)

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val string: String
  val foo: Foo

  @Provides fun provideString(): String = "parent"

  @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("parent")

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String
  val foo: Foo

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@BindingContainer
object FakeBindings {
  @Provides fun provideString(): String = "dynamic"

  @SingleIn(AppScope::class) @Provides fun provideFoo(): Foo = Foo("dynamic")
}

fun createChild(): ChildGraph {
  return createDynamicGraph<ParentGraph>(FakeBindings).childGraphFactory().create()
}
