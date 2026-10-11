// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

// Different key annotations can unwrap to the same runtime map key.
@MapKey annotation class FirstStringKey(val value: String)
@MapKey annotation class SecondStringKey(val value: String)

enum class Entry { VALUE }

@MapKey annotation class FirstEnumKey(val value: Entry)
@MapKey annotation class SecondEnumKey(val value: Entry)
@MapKey annotation class OtherClassKey(val value: KClass<*>)

@DependencyGraph
interface ExplicitKeysGraph {
  val <!DUPLICATE_MAP_KEY!>strings<!>: Map<String, Int>
  val <!DUPLICATE_MAP_KEY!>enums<!>: Map<Entry, Int>
  val <!DUPLICATE_MAP_KEY!>classes<!>: Map<KClass<*>, Int>

  @Provides @IntoMap @FirstStringKey("shared") fun firstString(): Int = 1
  @Provides @IntoMap @SecondStringKey("shared") fun secondString(): Int = 2

  @Provides @IntoMap @FirstEnumKey(Entry.VALUE) fun firstEnum(): Int = 1
  @Provides @IntoMap @SecondEnumKey(Entry.VALUE) fun secondEnum(): Int = 2

  @Provides @IntoMap @ClassKey(String::class) fun firstClass(): Int = 1
  @Provides @IntoMap @OtherClassKey(String::class) fun secondClass(): Int = 2
}

@MapKey(implicitClassKey = true)
annotation class InferredClassKey(val value: KClass<*> = Nothing::class)

interface Item
@Inject class ItemImpl : Item

@DependencyGraph
interface ImplicitKeysGraph {
  val <!DUPLICATE_MAP_KEY!>items<!>: Map<KClass<*>, Item>

  @Binds @IntoMap @InferredClassKey val ItemImpl.bindItem: Item

  @Provides @IntoMap @ClassKey(ItemImpl::class)
  fun explicitItem(): Item = ItemImpl()
}
