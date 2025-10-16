// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface AppGraph {
  val int: Int
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Int): AppGraph
  }
}

@BindingContainer
object Bindings {
  @Provides fun provideInt(): Int = 3
}

class Example {
  fun example() {
    class Local {
      fun localFun() {
        val graph = <!CREATE_DYNAMIC_GRAPH_ERROR!>createDynamicGraphFactory<AppGraph.Factory>(Bindings)<!>.create(4)
      }
    }

    object : Any() {
      init {
        val graph = <!CREATE_DYNAMIC_GRAPH_ERROR!>createDynamicGraphFactory<AppGraph.Factory>(Bindings)<!>.create(4)
      }
    }
  }
}
