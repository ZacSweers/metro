// GENERATE_CLASSES_IN_IR: false

package test

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class GenerateProvidesContribution(val scope: KClass<*>)

object FirstScope

@ContributesTo(FirstScope::class)
interface FirstContribution {
  val first: FirstValue
}

@Inject class FirstValue

@DependencyGraph(FirstScope::class)
interface FirstGraph

@GenerateProvidesContribution(AppScope::class)
class GeneratedValue

@ContributesTo(AppScope::class)
interface AppContribution {
  val generated: GeneratedValue
}

@DependencyGraph(AppScope::class, additionalScopes = [FirstScope::class])
interface AppGraph

fun box(): String {
  assertIs<FirstValue>(createGraph<FirstGraph>().first)
  val graph = createGraph<AppGraph>()
  assertIs<FirstValue>(graph.first)
  assertIs<GeneratedValue>(graph.generated)
  return "OK"
}
