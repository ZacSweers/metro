// https://github.com/ZacSweers/metro/issues/2919
// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

interface Handler

// Another library (like AutoDagger) binds this class into a map.
<!MAP_KEY_WITHOUT_CONTRIBUTES_INTO_MAP!>@StringKey("unsuppressed")<!>
@Inject
class UnsuppressedHandler : Handler

@Suppress("MAP_KEY_WITHOUT_CONTRIBUTES_INTO_MAP")
@StringKey("suppressed")
@Inject
class SuppressedHandler : Handler

@Module
interface HandlerModule {
  @Binds @IntoMap @StringKey("unsuppressed") fun bindUnsuppressed(impl: UnsuppressedHandler): Handler

  @Binds @IntoMap @StringKey("suppressed") fun bindSuppressed(impl: SuppressedHandler): Handler
}
