package test

// Custom annotation that triggers the test extension to generate a @DependencyGraph
// with a provider inside the annotated class
@Target(AnnotationTarget.CLASS)
annotation class GenerateProvidesInGraph

@ContributesTo(AppScope::class)
interface StringComponent {
  val text: String
}

@GenerateProvidesInGraph
class Application

// The extension generates a nested interface inside Application:
//   @IROnlyFactories
//   @DependencyGraph(AppScope::class)
//   interface AppGraph {
//     @Provides
//     fun provideString(): String {
//       return "Hello, @GenerateProvidesInGraph!"
//     }
//   }

fun box(): String {
  val graph = createGraph<Application.AppGraph>()
  assertEquals("Hello, @GenerateProvidesInGraph!", graph.text)
  return "OK"
}
