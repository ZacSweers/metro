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

@DependencyGraph(ParentScope::class, isExtendable = true)
interface ParentGraph {
  val messageService: MessageService
}

// MODULE: main(lib)
@DependencyGraph
interface ChildGraph {
  val messageService: MessageService

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Extends parentGraph: ParentGraph): ChildGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = createGraphFactory<ChildGraph.Factory>().create(parentGraph)
  assertTrue(childGraph.messageService is ParentMessageService)
  return "OK"
}