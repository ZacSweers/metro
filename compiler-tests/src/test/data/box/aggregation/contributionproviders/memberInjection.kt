// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

interface MessageService {
  fun message(): String
}

class Suffix(val value: String)

@HasMemberInjections
abstract class BaseService<T : Any> {
  @Inject @Named("prefix") lateinit var prefix: T
}

@Inject
@ContributesBinding(AppScope::class, binding = binding<MessageService>())
class MessageServiceImpl(prefix: Int = 3) : BaseService<String>(), MessageService {
  private val count = prefix

  @Inject var suffix: Suffix? = null

  private var injectionCount = 0

  @Inject
  private fun initialize(@Named("expectedPrefix") expectedPrefix: String, suffix: Provider<Suffix?>) {
    assertEquals(expectedPrefix, prefix)
    assertEquals("world", checkNotNull(suffix()).value)
    injectionCount++
  }

  override fun message(): String {
    assertEquals(1, injectionCount)
    return "$prefix $count ${checkNotNull(suffix).value}"
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: MessageService

  @Provides @Named("prefix") fun prefix(): String = "hello"

  @Provides @Named("expectedPrefix") fun expectedPrefix(): String = "hello"

  @Provides fun suffix(): Suffix? = Suffix("world")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("hello 3 world", graph.service.message())
  assertEquals("hello 3 world", graph.service.message())
  return "OK"
}
