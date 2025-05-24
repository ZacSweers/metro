// MODULE: lib

// FILE: Impl1.kt
package test1

@ContributesTo(AppScope::class)
interface ContributedInterface

// FILE: Impl2.kt
package test2

@ContributesTo(AppScope::class)
interface ContributedInterface

// MODULE: main(lib)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph