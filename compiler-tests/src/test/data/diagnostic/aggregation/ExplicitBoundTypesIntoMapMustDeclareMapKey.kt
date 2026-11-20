// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class, binding<ContributedInterface>())
@ClassKey // Class key is ignored if bound is explicit
@Inject
class Impl : ContributedInterface
