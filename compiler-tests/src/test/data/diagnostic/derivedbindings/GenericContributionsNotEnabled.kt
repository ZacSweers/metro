// RENDER_DIAGNOSTICS_FULL_TEXT

interface Cache<T>

@ContributesBinding(AppScope::class)
@ContributesBinding(UserScope::class)
@Inject
class LruCache<<!DERIVED_BINDINGS_NOT_ENABLED!>T<!>> : Cache<T>

@ContributesBinding(AppScope::class)
@Inject
class StringCache : Cache<String>

abstract class UserScope
