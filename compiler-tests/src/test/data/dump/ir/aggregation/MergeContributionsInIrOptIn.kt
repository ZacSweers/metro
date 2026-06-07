@ContributesTo(AppScope::class)
interface ContributedA {
  val a: String
}

@ContributesTo(AppScope::class)
interface ContributedB {
  val b: String
}

@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideA(): String = "a"
  @Provides fun provideB(): String = "b"
}
