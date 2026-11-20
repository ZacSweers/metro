// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class, binding<<!AGGREGATION_ERROR!>Unit<!>>())
@ClassKey
@Inject
class Impl : ContributedInterface
