// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class, binding<@ClassKey Any>())
@ClassKey
@Inject
class Impl : ContributedInterface
