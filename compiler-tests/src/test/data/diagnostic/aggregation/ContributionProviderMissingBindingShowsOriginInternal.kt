// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// MODULE: common
interface Repository

interface Dependency

// MODULE: lib(common)
@ContributesBinding(AppScope::class)
@Inject
internal class RepositoryImpl(private val dep: Dependency) : Repository

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface <!MISSING_BINDING!>AppGraph<!> {
  val repo: Repository
}
