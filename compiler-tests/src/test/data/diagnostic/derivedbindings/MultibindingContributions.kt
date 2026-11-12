// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

@BindingContainer
object Bindings {
  @Provides
  <!METRO_TYPE_PARAMETERS_ERROR!>@IntoSet<!>
  fun <T> intoSet(element: Encoder<T>): Encoder<List<T>> = object : Encoder<List<T>> {}

  @Provides
  <!METRO_TYPE_PARAMETERS_ERROR!>@ElementsIntoSet<!>
  fun <T> elementsIntoSet(element: Encoder<T>): Set<Encoder<List<T>>> = emptySet()

  @Provides
  <!METRO_TYPE_PARAMETERS_ERROR!>@IntoMap<!>
  @StringKey("key")
  fun <T> intoMap(element: Encoder<T>): Encoder<Set<T>> = object : Encoder<Set<T>> {}
}

@BindingContainer
interface BindsBindings {
  @Binds <!METRO_TYPE_PARAMETERS_ERROR!>@IntoSet<!> fun <T> bindIntoSet(impl: Encoder<List<T>>): Encoder<Collection<T>>
}
