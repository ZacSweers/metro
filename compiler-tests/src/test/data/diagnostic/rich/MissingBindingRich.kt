// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY
// DIAGNOSTICS_RENDER_MODE: RICH

// The rich missing-binding message highlights a similar binding.

interface Repository

@Inject
class RepositoryImpl(<!MISSING_BINDING!>dep: Dependency<!>) : Repository

interface Dependency

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@Inject
class NamedDependency(@Named("prod") val dep: String) : Dependency

@DependencyGraph
interface AppGraph {
  val repo: RepositoryImpl

  @Provides @Named("prod") fun provideProdDep(): String = "prod"
}
