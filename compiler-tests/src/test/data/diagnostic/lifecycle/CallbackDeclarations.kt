// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>abstractCallback<!>()

  @OnGraphInit fun returnsValue(): <!GRAPH_LIFECYCLE_ERROR!>String<!> = "value"

  @OnGraphInit private fun <!GRAPH_LIFECYCLE_ERROR!>privateCallback<!>() {}

  @OnGraphInit fun <T> <!GRAPH_LIFECYCLE_ERROR!>generic<!>() {}

  @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>String<!>.withReceiver() {}

  @OnGraphInit @OnGraphClose fun <!GRAPH_LIFECYCLE_ERROR!>both<!>() {}

  @Named("callback") @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>qualified<!>() {}

  @OnGraphClose fun defaultValue(<!GRAPH_LIFECYCLE_ERROR!>value<!>: String = "default") {}

  @OnGraphClose fun assisted(@Assisted <!GRAPH_LIFECYCLE_ERROR!>value<!>: String) {}
}

@OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>topLevel<!>() {}

open class RegularClass {
  @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>inClass<!>() {}
}

object SomeObject {
  @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>inObject<!>() {}
}

abstract class AbstractClassCallbacks {
  @OnGraphInit protected fun protectedCallback() {}

  @OnGraphInit internal fun <!GRAPH_LIFECYCLE_ERROR!>internalCallback<!>() {}
}
