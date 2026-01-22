@DependencyGraph(AppScope::class, excludes = [LongBinding1::class])
interface AppGraph {
    val unitGraph: UnitGraph
}

@GraphExtension(Unit::class)
interface UnitGraph {
    val long: Long
}

@ContributesTo(Unit::class)
@BindingContainer
object LongBinding1 {
    @Provides fun provideLong(): Long = 1L
}

@ContributesTo(Unit::class)
@BindingContainer
object LongBinding2 {
    @Provides fun provideLong(): Long = 2L
}

fun box(): String {
    val graph = createGraph<AppGraph>()
    assertEquals(2L, graph.unitGraph.long)
    return "OK"
}
