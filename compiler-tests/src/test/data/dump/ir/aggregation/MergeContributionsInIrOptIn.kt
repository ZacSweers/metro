class A

class B

@ContributesTo(AppScope::class)
interface ContributedA {
  val a: A
}

@ContributesTo(AppScope::class)
interface ContributedB {
  val b: B
}

@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideA(): A = A()
  @Provides fun provideB(): B = B()
}
