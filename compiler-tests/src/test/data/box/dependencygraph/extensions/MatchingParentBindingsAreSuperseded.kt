@DependencyGraph(bindingContainers = [Bindings::class], isExtendable = true)
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  @Binds val Int.bindNumber: Number
}

@BindingContainer
interface Bindings {
  @Binds val String.bindCharSequence: CharSequence

  companion object {
    @Provides fun provideString(): String = "Hello"
  }
}

@DependencyGraph(bindingContainers = [ChildBindings::class])
interface ChildGraph {
  val int: Int
  val number: Number
  val string: String
  val charSequence: CharSequence

  @Provides fun provideInt(): Int = 4

  @Binds val Int.bindNumber: Number

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Extends parent: AppGraph): ChildGraph
  }
}

@BindingContainer
interface ChildBindings {
  @Binds val String.bindCharSequence: CharSequence

  companion object {
    @Provides fun provideString(): String = "Hello child"
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = createGraphFactory<ChildGraph.Factory>().create(parent)
  assertEquals(4, child.int)
  assertEquals(4, child.number)
  assertEquals("Hello child", child.string)
  assertEquals("Hello child", child.charSequence)
  return "OK"
}
