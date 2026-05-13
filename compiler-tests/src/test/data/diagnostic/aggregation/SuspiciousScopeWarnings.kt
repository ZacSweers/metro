// RENDER_DIAGNOSTICS_FULL_TEXT

@Scope
annotation class Singleton

@Singleton
@DependencyGraph(AppScope::class)
interface AppGraph

@GraphExtension
interface LoggedIngraph

interface Base

@Inject
@ContributesIntoSet(<!SUSPICIOUS_AGGREGATION_SCOPE!>Singleton::class<!>)
class Impl : Base

@Inject
@ContributesIntoSet(<!SUSPICIOUS_AGGREGATION_SCOPE!>AppGraph::class<!>)
class Impl2 : Base

@Inject
@ContributesIntoSet(<!SUSPICIOUS_AGGREGATION_SCOPE!>LoggedIngraph::class<!>)
class Impl3 : Base
