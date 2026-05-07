@ContributesTo(AppScope::class)
interface ContributedAppComponent {
  val message: String
}

@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideMessage(): String = "OK"
}

fun box(): String {
  // The graph is annotated with @MergeContributionsInIr, so the contribution is added in IR
  // rather than via FIR supertype generation. Runtime behavior matches a normal contribution.
  val graph = createGraph<AppGraph>()
  assertEquals("OK", (graph as ContributedAppComponent).message)
  return "OK"
}
