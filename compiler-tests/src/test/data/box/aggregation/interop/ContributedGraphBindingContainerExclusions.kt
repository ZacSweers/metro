// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import com.squareup.anvil.annotations.ContributesSubcomponent
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Module

@ContributesSubcomponent(String::class, parentScope = AppScope::class, exclude = [IntBinding1::class])
interface StringGraph {
    val int: Int

    @ContributesSubcomponent.Factory
    @ContributesTo(AppScope::class)
    interface Factory {
        fun create(): StringGraph
    }
}

@MergeComponent(AppScope::class)
interface AppGraph {
    val unitGraph: UnitGraph
}

@ContributesTo(String::class)
@Module
object IntBinding1 {
  @Provides fun provideInt(): Int = 1
}

@ContributesTo(String::class)
@Module
object IntBinding2 {
  @Provides fun provideInt(): Int = 2
}

// Test MergeSubcomponent with exclude
@MergeSubcomponent(Unit::class, exclude = [LongBinding1::class])
interface UnitGraph {
    val long: Long
}

@ContributesTo(Unit::class)
@Module
object LongBinding1 {
  @Provides fun provideLong(): Long = 1L
}

@ContributesTo(Unit::class)
@Module
object LongBinding2 {
  @Provides fun provideLong(): Long = 2L
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val stringGraph = graph.asContribution<StringGraph.Factory>().create()
  assertEquals(2, stringGraph.int)
  assertEquals(2L, graph.unitGraph.long)
  return "OK"
}
