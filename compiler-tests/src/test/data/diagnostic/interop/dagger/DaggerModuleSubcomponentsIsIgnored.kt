// ENABLE_DAGGER_INTEROP
// RENDER_DIAGNOSTICS_FULL_TEXT

import dagger.Module
import dagger.Subcomponent

@Subcomponent
interface ChildSubcomponent

@Module(subcomponents = <!DAGGER_MODULE_SUBCOMPONENTS_WARNING!>[ChildSubcomponent::class]<!>)
interface ChildModule

// Empty subcomponents array should not warn.
@Module(subcomponents = [])
interface NoSubcomponentsModule

// Module without subcomponents argument should not warn.
@Module
interface PlainModule
