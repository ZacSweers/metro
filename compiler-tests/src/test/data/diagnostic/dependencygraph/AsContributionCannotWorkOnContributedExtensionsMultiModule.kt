// RENDER_DIAGNOSTICS_FULL_TEXT
// https://github.com/ZacSweers/metro/issues/774
// MODULE: lib

abstract class ChildScope

@ContributesGraphExtension(ChildScope::class)
interface ChildGraph {

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph

@Inject
class Foo

@ContributesTo(ChildScope::class)
interface FooProvider {
  val foo: Foo
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val childGraph = appGraph.asContribution<ChildGraph.Factory>().create()
  val foo = <!AS_CONTRIBUTION_ERROR!>childGraph<!>.asContribution<FooProvider>().foo
  return "OK"
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration, localProperty,
nestedClass, propertyDeclaration, stringLiteral */
