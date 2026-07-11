// ENABLE_DAGGER_INTEROP

import dagger.Lazy as DaggerLazy
import jakarta.inject.Provider as JakartaProvider
import javax.inject.Provider as JavaxProvider

@DependencyGraph(AppScope::class)
interface AppGraph

interface Foo

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FooImpl: Foo

interface DaggerLazyFoo

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DaggerLazyFooImpl : DaggerLazyFoo

interface ProviderFoo

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProviderFooImpl : ProviderFoo

interface JakartaProviderFoo

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JakartaProviderFooImpl : JakartaProviderFoo

@DependencyGraph(Unit::class)
interface FeatureGraph {
  val foo: Lazy<Foo>
  val daggerLazyFoo: DaggerLazyFoo
  val providerFoo: ProviderFoo
  val jakartaProviderFoo: JakartaProviderFoo

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes component: FeatureComponent): FeatureGraph
  }
}

@ContributesTo(AppScope::class)
interface FeatureComponent {
  val foo: Lazy<Foo>
  val daggerLazyFoo: DaggerLazy<DaggerLazyFoo>
  val providerFoo: JavaxProvider<ProviderFoo>
  val jakartaProviderFoo: JakartaProvider<JakartaProviderFoo>
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val featureGraph = createGraphFactory<FeatureGraph.Factory>().create(appGraph)
  assertNotNull(featureGraph.foo.value)
  assertNotNull(featureGraph.daggerLazyFoo)
  assertNotNull(featureGraph.providerFoo)
  assertNotNull(featureGraph.jakartaProviderFoo)
  return "OK"
}
