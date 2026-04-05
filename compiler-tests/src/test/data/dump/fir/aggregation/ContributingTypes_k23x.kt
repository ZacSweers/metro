// COMPILER_VERSION: 2.3

@ContributesTo(AppScope::class)
interface ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
