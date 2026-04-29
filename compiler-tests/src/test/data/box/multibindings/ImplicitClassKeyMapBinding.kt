import kotlin.reflect.KClass

interface Base

interface OtherBase

// ClassKey (built-in implicit class key) on contribution classes

// Implicit class key (no value specified)
@ClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class ImplicitImpl1 : Base

@ClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class ImplicitImpl2 : Base

// Explicit class key (different from the annotated class)
@ClassKey(Base::class)
@ContributesIntoMap(AppScope::class)
@Inject
class ExplicitDifferentImpl : Base

// Custom map key with implicitClassKey and a type projection

@MapKey(implicitClassKey = true)
annotation class CustomClassKey(val value: KClass<out OtherBase> = Nothing::class)

@CustomClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class CustomImplicit1 : OtherBase

@CustomClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class CustomImplicit2 : OtherBase

@CustomClassKey(OtherBase::class)
@ContributesIntoMap(AppScope::class)
@Inject
class CustomExplicit : OtherBase

// Wrapped key (unwrapValue = false, no implicit class key)

@MapKey(unwrapValue = false)
annotation class WrappedKey(val name: String, val id: Int)

// Binds with implicit class key

interface CharSequenceBase

@Inject class StringImpl : CharSequenceBase

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  // Explicit provides with ClassKey (explicit value required on callables)
  @Provides @IntoMap @ClassKey(String::class) fun provideStringEntry(): Base = object : Base {}

  // @Binds with implicit class key (receiver type is the implicit key)
  @Binds @IntoMap @ClassKey val StringImpl.bindImplicit: CharSequenceBase

  // Explicit provides with wrapped key
  @Provides @IntoMap @WrappedKey(name = "a", id = 1) fun provideWrappedA(): Base = object : Base {}
  @Provides @IntoMap @WrappedKey(name = "b", id = 2) fun provideWrappedB(): Base = object : Base {}

  val classKeyMap: Map<KClass<*>, Base>
  val customKeyMap: Map<KClass<out OtherBase>, OtherBase>
  val bindsKeyMap: Map<KClass<*>, CharSequenceBase>
  val wrappedKeyMap: Map<WrappedKey, Base>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // ClassKey map: implicit keys resolve to the annotated class, explicit keeps its value
  val classKeyMap = graph.classKeyMap
  assertEquals(4, classKeyMap.size)
  assertNotNull(classKeyMap[ImplicitImpl1::class])
  assertNotNull(classKeyMap[ImplicitImpl2::class])
  assertNotNull(classKeyMap[Base::class])
  assertNotNull(classKeyMap[String::class])

  // Custom key map: same implicit class key behavior with constrained type
  val customKeyMap = graph.customKeyMap
  assertEquals(3, customKeyMap.size)
  assertNotNull(customKeyMap[CustomImplicit1::class])
  assertNotNull(customKeyMap[CustomImplicit2::class])
  assertNotNull(customKeyMap[OtherBase::class])

  // Binds key map: implicit key resolves to the receiver type
  val bindsKeyMap = graph.bindsKeyMap
  assertEquals(1, bindsKeyMap.size)
  assertNotNull(bindsKeyMap[StringImpl::class])

  // Wrapped key map: no implicit class key, uses annotation itself as key
  val wrappedKeyMap = graph.wrappedKeyMap
  assertEquals(2, wrappedKeyMap.size)

  return "OK"
}
