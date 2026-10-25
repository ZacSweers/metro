private val seen = mutableListOf<String>()

interface Base {
  @OnGraphInit fun start(@Named("label") label: String, value: Int) {}
}

// An override repeats the qualifiers of the callback it overrides, and it can add new ones.
@DependencyGraph
interface AppGraph : CloseableGraph, Base {
  @Named("label") @Provides fun label(): String = "label"
  @Named("value") @Provides fun value(): Int = 1

  @OnGraphInit override fun start(@Named("label") label: String, @Named("value") value: Int) {
    seen += "$label $value"
  }
}

fun box(): String {
  createGraph<AppGraph>()
  return if (seen == listOf("label 1")) "OK" else "Fail: $seen"
}
