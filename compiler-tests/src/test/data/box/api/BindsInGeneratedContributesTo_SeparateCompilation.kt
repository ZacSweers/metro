// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MODULE: lib
package test

import kotlin.reflect.KClass

// Custom annotation that triggers the test extension to generate a @ContributesTo interface
// with a @Binds function
@Target(AnnotationTarget.CLASS)
annotation class GenerateBindsContribution(val scope: KClass<*>)

interface MyType

// The extension generates:
//   @ContributesTo(AppScope::class)
//   interface BindsContribution {
//     @Binds fun bindMyImpl(myImpl: MyImpl): MyType
//   }
// The bound type (MyType) is inferred from the class's first interface supertype.
@Inject
@GenerateBindsContribution(AppScope::class)
class MyImpl : MyType

// MODULE: main(lib)
package test

@DependencyGraph(AppScope::class)
interface AppGraph {
  val myType: MyType
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("test.MyImpl", graph.myType::class.qualifiedName)
  return "OK"
}
