// RENDER_DIAGNOSTICS_FULL_TEXT

interface Service

<!MULTIBINDS_ERROR!>@StringKey("bare")<!>
@Inject
class BareMapKeyWithoutIntoMap : Service

<!MULTIBINDS_ERROR!>@StringKey("class")<!>
@ContributesBinding(AppScope::class)
@Inject
class ClassMapKeyWithoutIntoMap : Service

@StringKey("valid-map")
@ContributesIntoMap(AppScope::class)
@ContributesBinding(
  AppScope::class,
  binding = binding<<!MULTIBINDS_ERROR!>@StringKey("binding")<!> Service>(),
)
@Inject
class BindingMapKeyOnNonMapContribution : Service

@StringKey("valid")
@ContributesBinding(AppScope::class)
@ContributesIntoMap(AppScope::class)
@Inject
class ClassMapKeyWithIntoMap : Service
