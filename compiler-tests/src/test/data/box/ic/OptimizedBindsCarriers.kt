// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.0
// ENABLE_OPTIMIZED_IC: true

// MODULE: lib

interface PublicBindsTarget
interface MixedPublicBindsTarget
interface PrivateBindsTarget

@Inject
class OptimizedBindsImpl : PublicBindsTarget, MixedPublicBindsTarget, PrivateBindsTarget

@BindingContainer
interface PublicBindsAliases {
  @Binds fun bindPublic(impl: OptimizedBindsImpl): PublicBindsTarget
}

@BindingContainer
interface MixedBindsAliases {
  @Binds fun bindVisible(impl: OptimizedBindsImpl): MixedPublicBindsTarget

  @Binds private fun bindPrivate(impl: OptimizedBindsImpl): PrivateBindsTarget = impl
}

// MODULE: main(lib)

@DependencyGraph(
  bindingContainers = [PublicBindsAliases::class, MixedBindsAliases::class]
)
interface OptimizedBindsGraph {
  val publicTarget: PublicBindsTarget
  val mixedPublicTarget: MixedPublicBindsTarget
  val privateTarget: PrivateBindsTarget
}

fun box(): String {
  val graph = createGraph<OptimizedBindsGraph>()
  assertTrue(graph.publicTarget is OptimizedBindsImpl)
  assertTrue(graph.mixedPublicTarget is OptimizedBindsImpl)
  assertTrue(graph.privateTarget is OptimizedBindsImpl)

  assertFalse(
    PublicBindsAliases::class.java.declaredClasses.any { it.simpleName == "BindsMirror" }
  )
  val privateMirror =
    MixedBindsAliases::class.java.declaredClasses.single { it.simpleName == "BindsMirror" }
  assertEquals(setOf("bindPrivate"), privateMirror.declaredMethods.mapTo(mutableSetOf()) { it.name })
  return "OK"
}
