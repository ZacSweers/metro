// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// DIAGNOSTICS_CONSOLE: RICH
// MAX_COMPILER_VERSION: 2.4.19

// Rich console rendering of a missing binding with a similar binding. The golden file uses
// AnsiMarkup-escaped ANSI codes (<b>, <red>, </>) — see MetroIrDiagnosticsHandler. Capped to
// <2.4.20 because the rich golden naming lives in Metro's legacy diagnostics handler.

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
