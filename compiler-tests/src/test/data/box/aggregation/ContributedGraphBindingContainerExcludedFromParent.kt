@DependencyGraph(AppScope::class, excludes = [LongBinding1::class, IntBinding1::class])
interface AppGraph {
    val unitGraph: UnitGraph
}

@GraphExtension(Unit::class, excludes = [StringBinding1::class])
interface UnitGraph {
    val long: Long
    val stringGraph: StringGraph
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

@ContributesTo(String::class)
@BindingContainer
object StringBinding1 {
    @Provides fun provideString(): String = "1"
}

@ContributesTo(String::class)
@BindingContainer
object StringBinding2 {
    @Provides fun provideString(): String = "2"
}

@GraphExtension(String::class)
interface StringGraph {
    val int: Int
    val string: String
}

@ContributesTo(String::class)
@BindingContainer
object IntBinding1 {
    @Provides fun provideInt(): Int = 1
}

@ContributesTo(String::class)
@BindingContainer
object IntBinding2 {
    @Provides fun provideInt(): Int = 2
}

fun box(): String {
    val graph = createGraph<AppGraph>()
    assertEquals(2L, graph.unitGraph.long)
    assertEquals(2, graph.unitGraph.stringGraph.int)
    assertEquals("2", graph.unitGraph.stringGraph.string)
    return "OK"
}
