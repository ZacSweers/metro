package test

// Custom annotation that triggers the test extension to generate a @GraphExtension
// with a @GraphExtension.Factory inside the annotated class
@Target(AnnotationTarget.CLASS)
annotation class GenerateGraphExtension

@ContributesTo(Unit::class)
interface StringComponent {
  val text: String
}

@GenerateGraphExtension
class LoginScreen

// The extension generates a nested interface inside Application:
//   @GraphExtension(Unit::class)
//   interface LoginGraph {
//     @ContributesTo(AppScope::class)
//     @GraphExtension.Factory
//     fun interface Factory {
//       fun create(@Provides text: String): LoginGraph
//     }
//   }

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val loginGraph = appGraph.asContribution<LoginScreen.LoginGraph.Factory>().create("hello")
  assertEquals("hello", (loginGraph as StringComponent).text)
  return "OK"
}
