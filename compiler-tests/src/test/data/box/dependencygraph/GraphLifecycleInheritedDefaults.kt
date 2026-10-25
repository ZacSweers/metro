// MODULE: lib
val seen = mutableListOf<String>()

interface CompiledBase {
  @OnGraphInit fun start(label: String = "compiled") {
    seen += "base $label"
  }
}

// MODULE: main(lib)
interface SourceBase {
  @OnGraphClose fun stop(label: String = "source") {
    seen += "base $label"
  }
}

// Overrides can't repeat a default, so their parameters use the overridden callback's default.
@DependencyGraph
interface AppGraph : CloseableGraph, CompiledBase, SourceBase {
  @OnGraphInit override fun start(label: String) {
    seen += "start $label"
  }

  @OnGraphClose override fun stop(label: String) {
    seen += "stop $label"
  }
}

fun box(): String {
  createGraph<AppGraph>().close()
  return if (seen == listOf("start compiled", "stop source")) "OK" else "Fail: $seen"
}
