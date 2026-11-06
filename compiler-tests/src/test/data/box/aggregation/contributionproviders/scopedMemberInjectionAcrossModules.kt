// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: common
interface MessageService {
  fun message(): String
}

interface OtherService

@HasMemberInjections
abstract class BaseService {
  private var text = "uninitialized"
  private var injectionCount = 0

  @Inject
  private fun initialize(@Named("message") text: String) {
    this.text = text
    injectionCount++
  }

  fun injectedMessage(): String {
    assertEquals(1, injectionCount)
    return text
  }
}

// MODULE: lib(common)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<MessageService>())
@ContributesBinding(AppScope::class, binding = binding<OtherService>())
@ContributesIntoSet(AppScope::class, binding = binding<MessageService>())
@ContributesIntoMap(AppScope::class, binding = binding<@StringKey("service") MessageService>())
internal class MessageServiceImpl : BaseService(), MessageService, OtherService {
  override fun message(): String = injectedMessage()
}

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: MessageService
  val other: OtherService
  val services: Set<MessageService>
  val servicesByName: Map<String, MessageService>

  @Provides @Named("message") fun message(): String = "injected"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("injected", graph.service.message())
  assertSame<Any>(graph.service, graph.other)
  assertSame(graph.service, graph.services.single())
  assertSame(graph.service, graph.servicesByName.getValue("service"))
  return "OK"
}
