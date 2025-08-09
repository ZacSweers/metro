@BindingContainer
abstract class Bindings {
  @Binds abstract val Int.unusedBinding: Number

  companion object {
    @Provides
    fun unusedString(int: Int): String {
      return int.toString()
    }
  }
}

@DependencyGraph(bindingContainers = [Bindings::class]) interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  return "OK"
}
