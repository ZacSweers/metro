// GENERATE_CLASSES_IN_IR: false

package test

import kotlin.reflect.KClass

// Custom annotation that triggers the test extension
@Target(AnnotationTarget.CLASS)
annotation class GenerateImpl(val scope: KClass<*>)

// External extension will generate: class Impl : Bar with @Inject and @ContributesBinding
// The contribution metadata is provided via MetroContributionExtension
@GenerateImpl(AppScope::class)
interface Foo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val foo = createGraph<AppGraph>().foo
  assertIs<Foo.Impl>(foo)
  return "OK"
}
