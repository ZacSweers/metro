@DependencyGraph
interface AppGraph {
  val message: Message

  // Bind MessageImpl as Message
  @Binds private val MessageImpl.bind: Message get() = this
}

interface Message

@Inject
class MessageImpl : Message

fun box(): String {
  assertNotNull(createGraph<AppGraph>().message)
  return "OK"
}