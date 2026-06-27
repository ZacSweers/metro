// RENDER_DIAGNOSTICS_FULL_TEXT

interface Base

@Inject class ValidFoo
@Inject class Impl : Base
class NoInject
@Inject class GenericFoo<T>
abstract class AbstractFoo

@SingleIn(AppScope::class)
@Inject class ScopedFoo

interface ExampleGraph {
  // Ok: parameterless @Binds explicitly claims ValidFoo's constructor-injected key.
  @Binds fun bindValid(): ValidFoo

  // Ok: classic @Binds still has a real source key.
  @Binds fun Impl.bindClassicExtension(): Base
  @Binds fun bindClassicParameter(impl: Impl): Base

  @Binds val <!BINDS_ERROR!>propertyBind<!>: ValidFoo

  @Binds fun bindNoInject(): <!BINDS_ERROR!>NoInject<!>

  @Binds fun bindGeneric(): <!BINDS_ERROR!>GenericFoo<String><!>

  @Binds fun bindAbstract(): <!BINDS_ERROR!>AbstractFoo<!>

  @Binds <!BINDS_ERROR!>@Named("named")<!> fun bindQualified(): ValidFoo

  @Binds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> fun bindScoped(): ValidFoo

  @GraphPrivate @Binds fun <!BINDS_ERROR!>bindGraphPrivate<!>(): ValidFoo

  @IntoSet @Binds fun <!BINDS_ERROR!>bindIntoSet<!>(): ValidFoo

  @IntoMap @ClassKey(ValidFoo::class) @Binds fun <!BINDS_ERROR!>bindIntoMap<!>(): ValidFoo

  @Binds fun bindScopedReturn(): <!BINDS_ERROR!>ScopedFoo<!>
}
