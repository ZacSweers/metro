// MERGED_SUPERTYPE_CHUNK_SIZE: 2

@ContributesTo(AppScope::class)
interface ContributedA {
  val a: String
}

@ContributesTo(AppScope::class)
interface ContributedB {
  val b: String
}

@ContributesTo(AppScope::class)
interface ContributedC {
  val c: String
}

@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideA(): String = "a"
}
