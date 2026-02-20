// FIR reports (from @ContributesTo)
// CHECK_REPORTS: merging-unmatched-replacements-fir-AppGraph
// CHECK_REPORTS: merging-unmatched-exclusions-fir-ExcludingSupertypeGraph
// IR reports (from graph extensions)
// CHECK_REPORTS: merging-unmatched-replacements-ir-kotlin_Unit
// CHECK_REPORTS: merging-unmatched-exclusions-ir-kotlin_Unit

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childGraph: ChildGraph
}

// FIR: This contributor replaces NonExistentModule, which doesn't exist as a contributor
@ContributesTo(AppScope::class, replaces = [NonExistentModule::class])
interface ReplacingSupertype {
  @Provides fun provideString(): String = "hello"
}

// FIR: This contributor excludes NonExistentExcludedModule, which doesn't exist as a contributor
@DependencyGraph(AppScope::class, excludes = [NonExistentExcludedModule::class])
interface ExcludingSupertypeGraph

// Placeholders - these exist but are NOT contributors
abstract class NonExistentModule
abstract class NonExistentExcludedModule

// Child graph that extends AppGraph with IR-level exclusions/replacements
@GraphExtension(
  scope = Unit::class,
  excludes = [NonExistentIrExclusion::class],
)
interface ChildGraph {
  val childString: String
}

@ContributesTo(Unit::class, replaces = [NonExistentIrReplacement::class])
interface ReplacingChildSupertype {
  @Provides fun provideString(): String = "hello"
}

// Placeholders for IR-level exclusions/replacements
abstract class NonExistentIrReplacement
abstract class NonExistentIrExclusion
