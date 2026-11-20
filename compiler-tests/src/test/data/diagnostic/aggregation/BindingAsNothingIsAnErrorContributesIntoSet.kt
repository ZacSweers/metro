// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class, binding<<!AGGREGATION_ERROR!>Nothing<!>>())
@Inject
class Impl : ContributedInterface
