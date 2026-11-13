// A graph extension uses its parent's unscoped generic providers and binds functions. They come
// from an object container, the parent graph itself, an instance container the parent holds, and
// a binds container.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

class Tag<T>(val name: String)

class Labeled<T>(val tag: Tag<T>, val label: String)

interface Source<T> {
  val tag: Tag<T>
}

@Inject class TagSource<T>(override val tag: Tag<T>) : Source<T>

@BindingContainer
object ObjectEncoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.name})")
}

@BindingContainer
class Labels(private val label: String) {
  @Provides fun <T> labeled(tag: Tag<T>): Labeled<T> = Labeled(tag, label)
}

@BindingContainer
interface SourceBindings {
  @Binds fun <T> source(impl: TagSource<T>): Source<T>
}

@DependencyGraph(bindingContainers = [ObjectEncoders::class, SourceBindings::class])
interface ParentGraph {
  val childFactory: ChildGraph.Factory

  // Declared on the graph, so the provider needs the parent graph instance.
  @Provides fun <T> tag(): Tag<T> = Tag("parent")

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes labels: Labels): ParentGraph
  }
}

@GraphExtension
interface ChildGraph {
  val listEncoder: Encoder<List<List<String>>>
  val intTag: Tag<Int>
  val labeled: Labeled<String>
  val source: Source<Long>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraphFactory<ParentGraph.Factory>().create(Labels("label"))
  val child = parent.childFactory.create()
  assertEquals("list(list(string))", child.listEncoder.name)
  assertEquals("parent", child.intTag.name)
  assertEquals("label", child.labeled.label)
  assertEquals("parent", child.labeled.tag.name)
  assertTrue(child.source is TagSource<*>)
  assertEquals("parent", child.source.tag.name)
  return "OK"
}
