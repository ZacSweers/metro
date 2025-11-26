// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

interface Generic<T>

annotation class MissingMapKey(val input1: String, val input2: String)
@MapKey
annotation class UnwrappedCustomKey(val input1: String)
@MapKey(unwrapValue = false)
annotation class WrappedCustomKey(val input1: String, val input2: String)

@BindingContainer
interface Bindings {
  // Bad
  @Multibinds
  val notASet: <!MULTIBINDS_ERROR!>Generic<String><!>
  @Multibinds
  val starSet: <!MULTIBINDS_ERROR!>Set<*><!>
  @Multibinds
  val providerStar: <!MULTIBINDS_ERROR!>Set<Provider<*>><!>
  @Multibinds
  val mapKeyStar: <!MULTIBINDS_ERROR!>Map<*, String><!>
  @Multibinds
  val mapValueStar: <!MULTIBINDS_ERROR!>Map<String, *><!>
  @Multibinds
  val mapValueProviderStar: <!MULTIBINDS_ERROR!>Map<String, Provider<*>><!>
  @Multibinds
  val mapValueProviderOfLazyStar: <!MULTIBINDS_ERROR!>Map<String, Provider<Lazy<*>>><!>
  @Multibinds
  val mapDoubleStar: <!MULTIBINDS_ERROR!>Map<*, *><!>

  // Invalid key types
  @Multibinds
  val mapNonKey: <!MULTIBINDS_ERROR!>Map<Generic<String>, String><!>
  @Multibinds
  val mapArrayKey: <!MULTIBINDS_ERROR!>Map<Array<String>, String><!>
  @Multibinds
  val mapMissingKey: <!MULTIBINDS_ERROR!>Map<MissingMapKey, String><!>
  @Multibinds
  val mapUnwrappedKey: <!MULTIBINDS_ERROR!>Map<UnwrappedCustomKey, String><!>

  // Ok
  @Multibinds
  val starGenericSet: Set<Generic<*>>
  @Multibinds
  val starMapValue: Map<String, Generic<*>>
  @Multibinds
  val starMapProviderValue: Map<String, Provider<Generic<*>>>
  @Multibinds
  val starMapProviderOfLazyValue: Map<String, Provider<Lazy<Generic<*>>>>
  // Valid key types
  @Multibinds
  val stringMap: Map<String, Generic<*>>
  @Multibinds
  val intMap: Map<Int, Generic<*>>
  @Multibinds
  val kclassMap: Map<KClass<*>, Generic<*>>
  @Multibinds
  val mapWrappedKey: Map<WrappedCustomKey, String>
}
