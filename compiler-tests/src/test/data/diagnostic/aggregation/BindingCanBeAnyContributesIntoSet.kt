// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class, binding<Any>())
@Inject
class Impl : ContributedInterface
