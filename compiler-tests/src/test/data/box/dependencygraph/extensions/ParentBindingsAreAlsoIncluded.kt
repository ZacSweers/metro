// MODULE: lib

abstract class ParentScope

interface MessageService {
  fun getMessage(): String
}

@Inject
@SingleIn(ParentScope::class)
@ContributesBinding(ParentScope::class)
class ParentMessageService : MessageService {
  override fun getMessage(): String = "Message from parent"
}

@GraphExtension
interface ChildGraph {
  val messageService: MessageService

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(ParentScope::class)
interface ParentGraph {
  val messageService: MessageService

  fun childGraphFactory(): ChildGraph.Factory
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.childGraphFactory().create()
  assertTrue(childGraph.messageService is ParentMessageService)
  return "OK"
}