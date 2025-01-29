// MODULE: lib

@ContributesTo(AppScope::class)
interface ContributedInterface

// MODULE: main(lib)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
