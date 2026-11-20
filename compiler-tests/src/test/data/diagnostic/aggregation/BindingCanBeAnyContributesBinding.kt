// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface ContributedInterface

@ContributesBinding(AppScope::class, binding<Any>())
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
