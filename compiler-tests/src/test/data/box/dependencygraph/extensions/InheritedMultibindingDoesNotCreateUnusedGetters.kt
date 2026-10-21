// METRO_JVM_ONLY
// OMIT_REDUNDANT_MIRRORS: true

@SingleIn(AppScope::class) @Inject class Scoped

interface Entry {
  val scoped: Scoped
}

// The graph dependency keeps construction in the map's owner.
@Inject class InjectedEntry(override val scoped: Scoped, val owner: ChildGraph) : Entry
@Inject class SharedByChildren(val scoped: Scoped)
@Inject
class DefaultedConsumer(val owner: NestedGraph, val entry: InjectedEntry = error("Missing entry"))

@DependencyGraph(AppScope::class)
interface RootGraph {
  val child: ChildGraph
  val sibling: SiblingGraph
}

@GraphExtension
interface ChildGraph {
  @Multibinds val entries: Map<String, Provider<Entry>>
  val shared: SharedByChildren
  val nested: NestedGraph

  @Provides @IntoMap @StringKey("entry") fun entry(entry: InjectedEntry): Entry = entry
}

@GraphExtension
interface NestedGraph {
  val shared: SharedByChildren
  val defaulted: DefaultedConsumer

  // The nested graph validates the inherited map but doesn't expose it.
  @Provides @IntoMap @StringKey("nested") fun nestedEntry(entry: InjectedEntry): Entry = entry
}

@GraphExtension
interface SiblingGraph {
  val shared: SharedByChildren
  val lazyScoped: Lazy<Scoped>
}

fun box(): String {
  val root = createGraph<RootGraph>()
  val child = root.child
  val provider = child.entries.getValue("entry")
  val first = provider()
  assertNotSame(first, provider())
  val sibling = root.sibling
  val shared = child.shared
  assertSame(first.scoped, shared.scoped)
  assertNotSame(shared, child.shared)
  val lazy = sibling.lazyScoped
  assertSame(lazy.value, lazy.value)
  assertSame(first.scoped, lazy.value)
  assertNotSame(shared, sibling.shared)
  val nested = child.nested
  assertNotSame(shared, nested.shared)
  assertSame(first.scoped, nested.defaulted.entry.scoped)
  assertSame(first.scoped, sibling.shared.scoped)
  return "OK"
}

// CHECK_BYTECODE_TEXT
// @RootGraph$Impl.class:
// 1 NEW SharedByChildren
// @RootGraph$Impl$ChildGraphImpl.class:
// 0 private final getEntry
// 0 getEntry\$annotations
// 0 private final getInjectedEntry
// 0 NEW InjectedEntry
// 1 INVOKESTATIC InjectedEntry\$MetroFactory\.create
// 1 RootGraph\$Impl.*getSharedByChildren
// @RootGraph$Impl$ChildGraphImpl$NestedGraphImpl.class:
// 1 RootGraph\$Impl.*getSharedByChildren
// @RootGraph$Impl$SiblingGraphImpl.class:
// 1 RootGraph\$Impl.*getSharedByChildren
