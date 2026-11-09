// METRO_JVM_ONLY

// FILE: Aa.kt
// Both outer names use 214 UTF-8 bytes. The Aa and BB suffixes have the same hash.
class Outer長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前名Aa {
  @DependencyGraph(AppScope::class, excludes = [ExcludedBindings::class])
  interface Root : RootAccessors
}

typealias FirstRoot = Outer長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前名Aa.Root

// FILE: BB.kt
class Outer長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前名BB {
  @DependencyGraph(AppScope::class, excludes = [ExcludedBindings::class])
  interface Root : RootAccessors
}

typealias SecondRoot = Outer長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前長い名前名BB.Root

// FILE: box.kt
interface RootAccessors {
  val child: ChildGraphExtensionWithALongDescriptiveName
  @Provides fun value(): String = "OK"
}

@GraphExtension
interface ChildGraphExtensionWithALongDescriptiveName {
  val leaf: Leaf
}

@GraphExtension(Unit::class)
interface Leaf {
  val value: String
}

@ContributesTo(Unit::class)
@BindingContainer
object ExcludedBindings {
  @Provides fun value(): String = "excluded"
}

fun box(): String {
  val first = createGraph<FirstRoot>().child
  val second = createGraph<SecondRoot>().child
  assertNull(first.javaClass.enclosingClass)
  assertNull(second.javaClass.enclosingClass)
  assertNotEquals(first.javaClass, second.javaClass)
  assertEquals("OK", first.leaf.value)
  return second.leaf.value
}
