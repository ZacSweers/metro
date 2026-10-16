// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

interface Service
@Inject @SingleIn(AppScope::class) class ScopedService : Service
@Inject class Temporary
@Inject class Nested(val temporary: Temporary)
class Supplied
data class Included(val supplied: Supplied)

@DependencyGraph(AppScope::class)
interface Parent : CloseableGraph {
  val child: Child
  @Binds val ScopedService.service: Service

  @OnGraphClose fun finish(service: Service, supplied: Supplied) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides supplied: Supplied): Parent
  }
}

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit fun initialize(service: Service, supplied: Supplied) {}
}

@DependencyGraph
interface IncludesGraph : CloseableGraph {
  @OnGraphClose
  fun finish(<!UNSCOPED_LIFECYCLE_PARAMETER!>supplied: Supplied<!>) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes included: Included): IncludesGraph
  }
}

@DependencyGraph
interface IndependentlySuppressed : CloseableGraph {
  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER")
  @OnGraphClose
  fun first(<!CLOSE_ONLY_BINDING!>temporary: Temporary<!>) {}

  @Suppress("CLOSE_ONLY_BINDING")
  @OnGraphClose
  fun second(<!UNSCOPED_LIFECYCLE_PARAMETER!>temporary: Temporary<!>) {}

  @OnGraphClose
  fun nested(<!CLOSE_ONLY_BINDING, UNSCOPED_LIFECYCLE_PARAMETER!>nested: Nested<!>) {}
}
