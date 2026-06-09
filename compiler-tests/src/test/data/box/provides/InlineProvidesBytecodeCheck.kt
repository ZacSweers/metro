@BindingContainer
object Bindings {
  @Provides inline fun provideInt(): Int = 3

  @Provides
  @PublishedApi
  internal inline fun provideFloat(): Float = 3F
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val int: Int
  val float: Float
}

fun box(): String {
  return "OK"
}

/*
Four of each appear
1. Provides declaration
2. newInstance()
3. Factory.invoke()
4. Accessor
*/

// <count> <instruction>
// CHECK_BYTECODE_TEXT
// 4 ICONST_3
// 4 LDC 3.0
