// ENABLE_DAGGER_INTEROP
// MODULE: main
@DependencyGraph(AppScope::class)
interface ExampleGraph {
    val stringGraph: StringGraph
}

@GraphExtension(String::class)
interface StringGraph

interface Dependency

interface OtherDependency

@ContributesBinding(String::class)
class DependencyImpl @Inject constructor(
    private val other: OtherDependency
) : Dependency

@dagger.Module
@ContributesTo(AppScope::class)
interface OptionalBindingModule {
    @dagger.BindsOptionalOf
    fun bindOptionalDependency(): Dependency
}

fun box(): String {
    val graph = createGraph<ExampleGraph>()
    return "OK"
}