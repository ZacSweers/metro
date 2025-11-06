// RENDER_DIAGNOSTICS_FULL_TEXT
// WITH_DAGGER
// WITH_ANVIL
// INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY: ERROR

import com.squareup.anvil.annotations.MergeComponent

interface Providers

@dagger.Module
interface Bindings

@MergeComponent(
  /* scope = */ <!INTEROP_ANNOTATION_ARGS_ERROR!>AppScope::class<!>,
  /* modules = */ <!INTEROP_ANNOTATION_ARGS_ERROR!>[Bindings::class]<!>,
  /* dependencies = */ <!INTEROP_ANNOTATION_ARGS_ERROR!>[Providers::class]<!>
)
interface AppComponent
