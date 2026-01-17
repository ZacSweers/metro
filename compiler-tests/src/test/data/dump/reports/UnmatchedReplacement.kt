// CHECK_REPORTS: merging-unmatched-replacements-ir-dev_zacsweers_metro_AppScope

@DependencyGraph(AppScope::class)
interface AppGraph

// This contributor replaces NonExistentModule, which doesn't exist
@ContributesTo(AppScope::class, replaces = [NonExistentModule::class])
interface ReplacingSupertype {
  @Provides fun provideString(): String = "hello"
}

// Placeholder for the replaced class - it exists but is NOT a contributor
abstract class NonExistentModule
