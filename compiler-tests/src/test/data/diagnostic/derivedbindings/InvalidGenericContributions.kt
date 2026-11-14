// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Cache<T>

interface Marker

<!METRO_TYPE_PARAMETERS_ERROR!>@ContributesIntoSet(AppScope::class)<!>
@Inject
class SetCache<T> : Cache<T>

<!METRO_TYPE_PARAMETERS_ERROR!>@ContributesIntoMap(AppScope::class)<!>
@StringKey("cache")
@Inject
class MapCache<T> : Cache<T>

@ContributesBinding(AppScope::class)
@Inject
class MarkerImpl<<!METRO_TYPE_PARAMETERS_ERROR!>T<!>> : Marker

@ContributesBinding(AppScope::class)
@Inject
class PartialCache<K, <!METRO_TYPE_PARAMETERS_ERROR!>V<!>> : Cache<K>
