// WITH_ANVIL
// ENABLE_DAGGER_INTEROP

import com.squareup.anvil.annotations.MergeComponent
import dagger.Module

@MergeComponent(AppScope::class, exclude = [IntBinding1::class])
interface AppGraph {
  val int: Int
}

@ContributesTo(AppScope::class)
@Module
object IntBinding1 {
  @Provides fun provideInt(): Int = 1
}

@ContributesTo(AppScope::class)
@Module
object IntBinding2 {
  @Provides fun provideInt(): Int = 2
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(2, graph.int)
  return "OK"
}
