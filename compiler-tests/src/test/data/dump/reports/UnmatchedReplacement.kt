// RUN_PIPELINE_TILL: BACKEND
// FIR reports (from @ContributesTo)
// CHECK_REPORTS: merging-unmatched-replacements-fir/AppGraph
// CHECK_REPORTS: merging-unmatched-exclusions-fir/ExcludingSupertypeGraph
// IR reports (from graph extensions)
// CHECK_REPORTS: merging-unmatched-replacements-ir/kotlin/Unit
// CHECK_REPORTS: merging-unmatched-exclusions-ir/kotlin/Unit

@DependencyGraph(AppScope::class)
interface AppGraph

// The contributed accessor exercises FIR supertype merging alongside the binding container.
@ContributesTo(AppScope::class)
interface ChildGraphAccessor {
  val childGraph: ChildGraph
}

// FIR: This contributor replaces NonExistentModule, which doesn't exist as a contributor
@ContributesTo(AppScope::class, replaces = [NonExistentModule::class])
@BindingContainer
object ReplacingContainer {
  @Provides fun provideString(): String = "hello"
}

// FIR: Keep this exclusion in an independent scope so it has no contributed child graph.
@DependencyGraph(ExcludingSupertypeGraph::class, excludes = [NonExistentExcludedModule::class])
interface ExcludingSupertypeGraph

// Placeholders - these exist but are NOT contributors
abstract class NonExistentModule

abstract class NonExistentExcludedModule

// Child graph that extends AppGraph with IR-level exclusions/replacements
@GraphExtension(scope = Unit::class, excludes = [NonExistentIrExclusion::class])
interface ChildGraph {
  val childString: String
}

@ContributesTo(Unit::class, replaces = [NonExistentIrReplacement::class])
@BindingContainer
object ReplacingChildContainer {
  @Provides fun provideString(): String = "hello"
}

// Placeholders for IR-level exclusions/replacements
abstract class NonExistentIrReplacement

abstract class NonExistentIrExclusion
