// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.0
// OMIT_REDUNDANT_MIRRORS: true

// MODULE: lib

interface PublicBindsTarget
interface MixedPublicBindsTarget
interface PrivateBindsTarget

@Inject
class DirectBindsImpl : PublicBindsTarget, MixedPublicBindsTarget, PrivateBindsTarget

@BindingContainer
interface PublicBindsAliases {
  @Binds fun bindPublic(impl: DirectBindsImpl): PublicBindsTarget
}

@BindingContainer
interface MixedBindsAliases {
  @Binds fun bindVisible(impl: DirectBindsImpl): MixedPublicBindsTarget

  @Binds private fun bindPrivate(impl: DirectBindsImpl): PrivateBindsTarget = impl
}

// MODULE: main(lib)

@DependencyGraph(
  bindingContainers = [PublicBindsAliases::class, MixedBindsAliases::class]
)
interface DirectBindsGraph {
  val publicTarget: PublicBindsTarget
  val mixedPublicTarget: MixedPublicBindsTarget
  val privateTarget: PrivateBindsTarget
}

fun box(): String {
  val graph = createGraph<DirectBindsGraph>()
  assertTrue(graph.publicTarget is DirectBindsImpl)
  assertTrue(graph.mixedPublicTarget is DirectBindsImpl)
  assertTrue(graph.privateTarget is DirectBindsImpl)

  assertFalse(
    PublicBindsAliases::class.java.declaredClasses.any { it.simpleName == "BindsMirror" }
  )
  val privateMirror =
    MixedBindsAliases::class.java.declaredClasses.single { it.simpleName == "BindsMirror" }
  assertEquals(setOf("bindPrivate"), privateMirror.declaredMethods.mapTo(mutableSetOf()) { it.name })
  return "OK"
}
