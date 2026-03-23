// COMPILER_VERSION: 2.4

@ContributesTo(AppScope::class)
interface ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
