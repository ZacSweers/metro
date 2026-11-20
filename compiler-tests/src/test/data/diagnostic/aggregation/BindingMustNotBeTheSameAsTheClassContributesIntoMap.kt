// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class, binding<<!AGGREGATION_ERROR!>Impl<!>>())
@ClassKey
@Inject
class Impl : ContributedInterface
