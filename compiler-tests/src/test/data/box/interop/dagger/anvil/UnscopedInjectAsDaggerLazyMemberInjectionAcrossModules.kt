// https://github.com/ZacSweers/metro/issues/2509
// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// MODULE: lib

class Anything @Inject constructor()

// MODULE: main(lib)

import com.squareup.anvil.annotations.ContributesTo
import dagger.Lazy
import javax.inject.Provider as JavaxProvider

@ContributesTo(AppScope::class)
interface ApplicationComponent {
  fun inject(injected: GetsInjected)
}

class GetsInjected {
  @Inject lateinit var anything: Lazy<Anything>
  @Inject lateinit var javaxAnything: JavaxProvider<Anything>
  @Inject lateinit var metroAnything: Provider<Anything>

  lateinit var setterAnything: Lazy<Anything>
  lateinit var setterJavaxAnything: JavaxProvider<Anything>
  lateinit var setterMetroAnything: Provider<Anything>

  @Inject
  fun injectAnything(anything: Lazy<Anything>) {
    setterAnything = anything
  }

  @Inject
  fun injectProviders(
    javaxAnything: JavaxProvider<Anything>,
    metroAnything: Provider<Anything>,
  ) {
    setterJavaxAnything = javaxAnything
    setterMetroAnything = metroAnything
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val injected = GetsInjected()
  graph.inject(injected)
  assertNotNull(injected.anything)
  assertNotNull(injected.anything.get())
  assertNotNull(injected.javaxAnything)
  assertNotNull(injected.javaxAnything.get())
  assertNotNull(injected.metroAnything)
  assertNotNull(injected.metroAnything())
  assertNotNull(injected.setterAnything)
  assertNotNull(injected.setterAnything.get())
  assertNotNull(injected.setterJavaxAnything)
  assertNotNull(injected.setterJavaxAnything.get())
  assertNotNull(injected.setterMetroAnything)
  assertNotNull(injected.setterMetroAnything())
  return "OK"
}
