// RUN_PIPELINE_TILL: FRONTEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Soft warning: an @Inject class taking a `@SuspendAware` class as a ctor param *probably*
// needs to be `@SuspendAware` itself, but it might be supplied manually (e.g. via @BindsInstance)
// so we warn rather than error. IR validation will produce a hard error if the chain actually
// holds.

@SuspendAware
@Inject
class Inner(val s: String)

@Inject
class Outer(<!METRO_WARNING!>val inner: Inner<!>)
