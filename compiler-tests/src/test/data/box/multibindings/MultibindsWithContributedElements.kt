// METRO_JVM_ONLY

import kotlin.jvm.JvmSuppressWildcards

interface MultiboundType

@Inject
class MultiImpl : MultiboundType

@ContributesTo(AppScope::class)
interface MultibindingsModule {
  @Provides @ElementsIntoSet
  fun provideMulti(impl: MultiImpl): Set<@JvmSuppressWildcards MultiboundType> = setOf(impl)
}

@ContributesTo(AppScope::class)
interface MultibindingsModule2 {
  @Multibinds(allowEmpty = true)
  fun provideMulti(): Set<@JvmSuppressWildcards MultiboundType>
}

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val multi: Set<MultiboundType>
}

fun box(): String {
  assertIs<MultiImpl>(createGraph<ExampleGraph>().multi.single())
  return "OK"
}
