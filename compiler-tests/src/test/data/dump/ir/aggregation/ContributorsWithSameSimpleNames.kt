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

// Note the compiler test framework is ok with generating duplicate IR files, so we do this
// IR dump test to golden image it to watch for regressions
@DependencyGraph(scope = AppScope::class)
interface ExampleGraph