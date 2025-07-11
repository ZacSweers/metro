// Similar to the BindingContainerViaAnnotation test but all included transitively
@DependencyGraph(
  bindingContainers = [StringBindings::class]
)
interface AppGraph {
  val string: String
  val charSequence: CharSequence
  val int: Int
  val long: Long
}

// Simple class with a no-arg constructor
@BindingContainer(includes = [IntBindings::class])
class StringBindings {
  @Provides
  fun provideString(): String {
    return "string value"
  }
}

// Simple object
@BindingContainer(includes = [MixedBindings::class])
object IntBindings {
  @Provides
  fun provideInt(): Int {
    return 3
  }
}

// Interface with companion object and binds
@BindingContainer
interface MixedBindings {
  @Binds val String.bind: CharSequence

  companion object {
    @Provides
    fun provideLong(charSequence: CharSequence, int: Int): Long {
      return (charSequence.length + int).toLong()
    }
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string value", graph.string)
  assertEquals("string value", graph.charSequence)
  assertEquals(3, graph.int)
  assertEquals(15, graph.long)
  return "OK"
}
