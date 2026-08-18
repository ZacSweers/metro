// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// The producer's shortened names must be discovered from its metadata, even when the consuming
// graph uses a different name budget. These names reproduce the two Android D8 failure shapes.

// MODULE: common
abstract class AccountUserScope private constructor()
abstract class InterfaceOnlyScope private constructor()

interface Entry {
  fun value(): String
}

interface ObserveMapUserLocationStyleUseCase : Entry
interface LearningBlockReferenceContentViewModel : Entry
interface ObjectService {
  fun value(): String
}
interface AssistedValue {
  val text: String

  interface Factory {
    fun create(text: String): AssistedValue
  }
}

class RequiredValue(val value: String)
class AuthoredValue(val value: String)
class DefaultConfig(val value: String)
fun defaultConfig(): DefaultConfig = DefaultConfig("default")

// MODULE: lib(common)
// MAX_GENERATED_CLASS_NAME_LENGTH: 150
@ContributesBinding(AccountUserScope::class)
@ContributesIntoSet(AccountUserScope::class, binding = binding<Entry>())
@ContributesIntoSet(AccountUserScope::class, binding = binding<@Named("qualified") Entry>())
@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("map") Entry>())
@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("map-alias") Entry>())
@SingleIn(AccountUserScope::class)
@Inject
internal class ObserveMapUserLocationStyleUseCaseImpl(
  private val required: RequiredValue,
  private val config: DefaultConfig = defaultConfig(),
) : ObserveMapUserLocationStyleUseCase {
  override fun value(): String = required.value + "-" + config.value
}

@ContributesBinding(AccountUserScope::class)
@ContributesIntoSet(AccountUserScope::class, binding = binding<Entry>())
@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("learning") Entry>())
@Inject
internal class LearningBlockReferenceContentViewModelImpl(
  private val config: DefaultConfig = defaultConfig(),
) : LearningBlockReferenceContentViewModel {
  override fun value(): String = "learning-" + config.value
}

@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("unicode") Entry>())
@Inject
internal class 非常に長い名前非常に長い名前非常に長い名前非常に長い名前実装 : Entry {
  override fun value(): String = "unicode"
}

@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("first") Entry>())
@Inject
internal class CollectionEntryWithTheSameLongDescriptivePrefixThatMustBeShortenedForGeneratedClassesOne : Entry {
  override fun value(): String = "first"
}

@ContributesIntoMap(AccountUserScope::class, binding = binding<@StringKey("second") Entry>())
@Inject
internal class CollectionEntryWithTheSameLongDescriptivePrefixThatMustBeShortenedForGeneratedClassesTwo : Entry {
  override fun value(): String = "second"
}

@ContributesBinding(AccountUserScope::class)
internal object LongNamedObjectContributionWhoseGeneratedHolderMustBeShortenedAcrossCompilationBoundaries : ObjectService {
  override fun value(): String = "object"
}

// Assisted factories retain their existing direct contribution path.
@AssistedInject
class AssistedValueImpl(@Assisted override val text: String) : AssistedValue {
  @ContributesBinding(AccountUserScope::class)
  @AssistedFactory
  interface Factory : AssistedValue.Factory {
    override fun create(text: String): AssistedValueImpl
  }
}

@ContributesTo(InterfaceOnlyScope::class)
interface PureContributedInterface {
  @Provides fun interfaceOnlyValue(): String = "interface-only"
}

@BindingContainer
object AuthoredBindingContainerWhoseNameLeavesNoRoomForTheRequiredGeneratedFactoryAndCompanionAtTheConfiguredNameLimit {
  @Provides
  fun provideAValueWithDependencies(input: RequiredValue): AuthoredValue = AuthoredValue(input.value)
}

// MODULE: main(lib, common)
// MAX_GENERATED_CLASS_NAME_LENGTH: 233
@DependencyGraph(
  AccountUserScope::class,
  bindingContainers =
    [AuthoredBindingContainerWhoseNameLeavesNoRoomForTheRequiredGeneratedFactoryAndCompanionAtTheConfiguredNameLimit::class],
)
interface AppGraph {
  val mapUseCase: ObserveMapUserLocationStyleUseCase
  val learningViewModel: LearningBlockReferenceContentViewModel
  val objectService: ObjectService
  val assistedFactory: AssistedValue.Factory
  val authoredValue: AuthoredValue
  val entries: Set<Entry>
  @Named("qualified") val qualifiedEntries: Set<Entry>
  val entriesByName: Map<String, Entry>

  @Provides fun requiredValue(): RequiredValue = RequiredValue("required")
}

@DependencyGraph(InterfaceOnlyScope::class)
interface InterfaceOnlyGraph {
  val value: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("required-default", graph.mapUseCase.value())
  assertEquals("learning-default", graph.learningViewModel.value())
  assertEquals("object", graph.objectService.value())
  assertSame(graph.objectService, createGraph<AppGraph>().objectService)
  assertEquals("assisted", graph.assistedFactory.create("assisted").text)
  assertEquals("required", graph.authoredValue.value)
  assertEquals("interface-only", createGraph<InterfaceOnlyGraph>().value)
  assertEquals(setOf("required-default", "learning-default"), graph.entries.map { it.value() }.toSet())
  assertEquals(setOf("map", "map-alias", "learning", "unicode", "first", "second"), graph.entriesByName.keys)
  assertEquals("unicode", graph.entriesByName.getValue("unicode").value())
  assertEquals("first", graph.entriesByName.getValue("first").value())
  assertEquals("second", graph.entriesByName.getValue("second").value())
  assertSame(graph.mapUseCase, graph.entriesByName.getValue("map"))
  assertSame(graph.mapUseCase, graph.entriesByName.getValue("map-alias"))
  assertSame(graph.mapUseCase, graph.qualifiedEntries.single())
  return "OK"
}
