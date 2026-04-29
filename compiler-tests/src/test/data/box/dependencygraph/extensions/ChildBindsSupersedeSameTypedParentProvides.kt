// A child graph extension's @Binds should supersede a parent's @Provides of the
// same type. Previously this caused a DuplicateBinding error because
// collectInheritedData only checked node.providerFactories, not node.bindsCallables.
// https://github.com/ZacSweers/metro/issues/1885
//
// Also verifies that bindings propagate correctly through multi-level extensions
// (grandchild inherits from child, not parent) and that dynamic bindings from a
// createDynamicGraph parent correctly supersede inherited bindings in grandchildren.

@DependencyGraph
interface ParentGraph {
  val nullableString: String?

  @Provides fun provideNullString(): String? = null

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val presentString: String?
  val grandchild: GrandchildGraph

  @Provides fun provideString(): String = "hello"

  @Binds val String.bindAsNullable: String?

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val string: String?
}

@BindingContainer
object OverrideBindings {
  @Provides fun provideNullableString(): String? = "dynamic"
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(null, parent.nullableString)
  val child = parent.childGraphFactory().create()
  assertEquals("hello", child.presentString)

  // Grandchild should inherit child's binding, not parent's
  assertEquals("hello", child.grandchild.string)

  // Dynamic parent's binding container should override String? and propagate to children
  val dynamicParent = createDynamicGraph<ParentGraph>(OverrideBindings)
  assertEquals("dynamic", dynamicParent.nullableString)
  val dynamicChild = dynamicParent.childGraphFactory().create()
  assertEquals("dynamic", dynamicChild.presentString)
  assertEquals("dynamic", dynamicChild.grandchild.string)
  return "OK"
}
