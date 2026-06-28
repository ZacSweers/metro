// GENERATE_CONTRIBUTION_PROVIDERS: true
// RENDER_DIAGNOSTICS_FULL_TEXT

interface Service

@ContributesBinding(AppScope::class)
@Inject
class HiddenService : Service

interface ExposedService

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class ExposedServiceImpl : ExposedService

interface ExampleGraph {
  @Binds fun hidden(): <!BINDS_ERROR!>HiddenService<!>

  @Binds fun exposed(): ExposedServiceImpl
}
