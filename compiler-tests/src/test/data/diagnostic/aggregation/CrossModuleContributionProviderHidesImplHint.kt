// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Verifies the "Hidden behind a generated contribution provider" hint on similar bindings fires
// for bindings provided from a *precompiled* (library) module — exercising the IR reader for
// `@Origin(context = "contribution_provider")` against class metadata rather than freshly
// generated declarations.

// MODULE: lib
interface ChangelogRepository

class ChangelogRepositoryImpl : ChangelogRepository

@ContributesBinding(AppScope::class)
@Inject
class StubChangelogRepository(realImpl: ChangelogRepositoryImpl) : ChangelogRepository

// MODULE: main(lib)
@DependencyGraph(AppScope::class) interface <!MISSING_BINDING!>AppGraph<!> {
  val repo: ChangelogRepository
}
