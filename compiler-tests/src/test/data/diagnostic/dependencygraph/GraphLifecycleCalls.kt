interface Startup {
  @OnGraphInit fun start() {}
}

interface Feature : Startup {
  @OnGraphInit override fun start() {
    super.<!DEPENDENCY_GRAPH_ERROR!>start<!>()
  }
}

fun call(feature: Feature) {
  feature.<!DEPENDENCY_GRAPH_ERROR!>start<!>()
  val bound = feature::<!DEPENDENCY_GRAPH_ERROR!>start<!>
  val unbound = Feature::<!DEPENDENCY_GRAPH_ERROR!>start<!>
}
