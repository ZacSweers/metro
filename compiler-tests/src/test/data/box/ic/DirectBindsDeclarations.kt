// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.4.0
// OMIT_REDUNDANT_MIRRORS: true

// MODULE: lib

interface PublicBindsTarget
interface MixedPublicBindsTarget
interface PrivateBindsTarget
interface InheritedInternalBindsTarget

@Inject
class DirectBindsImpl :
  PublicBindsTarget,
  MixedPublicBindsTarget,
  PrivateBindsTarget,
  InheritedInternalBindsTarget

@BindingContainer
interface PublicBindsAliases {
  @Binds fun bindPublic(impl: DirectBindsImpl): PublicBindsTarget
}

@BindingContainer
interface MixedBindsAliases {
  @Binds fun bindVisible(impl: DirectBindsImpl): MixedPublicBindsTarget

  @Binds private fun bindPrivate(impl: DirectBindsImpl): PrivateBindsTarget = impl
}

abstract class InternalBindsBase {
  internal abstract fun bindInternal(impl: DirectBindsImpl): InheritedInternalBindsTarget
}

@BindingContainer
abstract class InheritedInternalBindsAliases : InternalBindsBase() {
  @Binds abstract override fun bindInternal(
    impl: DirectBindsImpl
  ): InheritedInternalBindsTarget
}

// MODULE: main(lib)

@DependencyGraph(
  bindingContainers = [
    PublicBindsAliases::class,
    MixedBindsAliases::class,
    InheritedInternalBindsAliases::class,
  ]
)
interface DirectBindsGraph {
  val publicTarget: PublicBindsTarget
  val mixedPublicTarget: MixedPublicBindsTarget
  val privateTarget: PrivateBindsTarget
  val inheritedInternalTarget: InheritedInternalBindsTarget
}

fun box(): String {
  val graph = createGraph<DirectBindsGraph>()
  assertTrue(graph.publicTarget is DirectBindsImpl)
  assertTrue(graph.mixedPublicTarget is DirectBindsImpl)
  assertTrue(graph.privateTarget is DirectBindsImpl)
  assertTrue(graph.inheritedInternalTarget is DirectBindsImpl)

  assertFalse(
    PublicBindsAliases::class.java.declaredClasses.any { it.simpleName == "BindsMirror" }
  )
  val privateMirror =
    MixedBindsAliases::class.java.declaredClasses.single { it.simpleName == "BindsMirror" }
  assertEquals(setOf("bindPrivate"), privateMirror.declaredMethods.mapTo(mutableSetOf()) { it.name })
  val inheritedInternalMirror =
    InheritedInternalBindsAliases::class.java.declaredClasses.single {
      it.simpleName == "BindsMirror"
    }
  assertEquals(
    setOf("bindInternal"),
    inheritedInternalMirror.declaredMethods.mapTo(mutableSetOf()) { it.name },
  )
  return "OK"
}
