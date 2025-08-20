@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  @Binds val Int.bindNumber: Number

  fun childGraphFactory(): ChildGraph.Factory
}

@BindingContainer
interface Bindings {
  @Binds val String.bindCharSequence: CharSequence

  companion object {
    @Provides fun provideString(): String = "Hello"
  }
}

@GraphExtension(bindingContainers = [ChildBindings::class])
interface ChildGraph {
  val int: Int
  val number: Number
  val string: String
  val charSequence: CharSequence

  @Provides fun provideInt(): Int = 4

  @Binds val Int.bindNumber: Number

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
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
  val child = parent.childGraphFactory().create()
  assertEquals(4, child.int)
  assertEquals(4, child.number)
  assertEquals("Hello child", child.string)
  assertEquals("Hello child", child.charSequence)
  return "OK"
}
