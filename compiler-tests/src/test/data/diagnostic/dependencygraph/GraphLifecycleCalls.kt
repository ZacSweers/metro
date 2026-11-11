// RENDER_DIAGNOSTICS_FULL_TEXT

interface Startup {
  @OnGraphInit fun start() {}
  @OnGraphClose fun stop() {}
}

// An override can run the callback it replaces through super.
interface Feature : Startup {
  @OnGraphInit override fun start() {
    super.start()
  }

  @OnGraphClose override fun stop() {
    run { super.stop() }
  }
}

// Other super calls run a callback outside Metro's control.
interface Misuse : Startup {
  @OnGraphInit override fun start() {
    super.<!DEPENDENCY_GRAPH_ERROR!>stop<!>()
  }

  @OnGraphInit fun warmUp() {
    super.<!DEPENDENCY_GRAPH_ERROR!>start<!>()
  }
}

fun call(feature: Feature) {
  feature.<!DEPENDENCY_GRAPH_ERROR!>start<!>()
  val bound = feature::<!DEPENDENCY_GRAPH_ERROR!>start<!>
  val unbound = Feature::<!DEPENDENCY_GRAPH_ERROR!>start<!>
}
