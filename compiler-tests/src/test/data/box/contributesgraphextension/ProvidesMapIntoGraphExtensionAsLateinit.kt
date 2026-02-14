// MODULE: lib
@DependencyGraph
interface FooComponent {
  fun subcomponent(): FooSubcomponent

  @Provides fun provideFoo(bar: Map<String, String>): String = "foo"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides bar: Map<String, String>): FooComponent
  }
}

@GraphExtension
interface FooSubcomponent {
  fun inject(activity: Activity)
}

class Activity {
  @Inject
  lateinit var foo: String
}

fun box(): String {
  val activity = Activity()
  createGraphFactory<FooComponent.Factory>()
    .create(bar = emptyMap())
    .subcomponent()
    .inject(activity)

  assertEquals("foo", activity.foo)
  return "OK"
}
