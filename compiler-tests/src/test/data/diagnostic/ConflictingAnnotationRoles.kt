// RENDER_DIAGNOSTICS_FULL_TEXT
// WITH_DAGGER
// GENERATE_CONTRIBUTION_PROVIDERS: true

// MODULE: lib
// FILE: ExternalRoles.kt

@Qualifier
@Scope
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class ExternalQualifierAndScope

// MODULE: main(lib)
// FILE: ConflictingAnnotationRoles.kt

@Qualifier
@MapKey
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class QualifierAndMapKey(val value: String)

@Scope
@MapKey
@Target(AnnotationTarget.FUNCTION)
annotation class ScopeAndMapKey(val value: String)

@Qualifier
@Scope
@MapKey
@Target(AnnotationTarget.FUNCTION)
annotation class AllRoles(val value: String)

@javax.inject.Qualifier
@javax.inject.Scope
@dagger.MapKey
@Target(AnnotationTarget.FUNCTION)
annotation class DaggerAndJsrRoles(val value: String)

<!CONFLICTING_ANNOTATION_ROLES!>@ExternalQualifierAndScope<!>
fun externalQualifierAndScope() = Unit

<!CONFLICTING_ANNOTATION_ROLES!>@QualifierAndMapKey(value = "qualifier-map-key")<!>
fun qualifierAndMapKey() = Unit

<!CONFLICTING_ANNOTATION_ROLES!>@ScopeAndMapKey(value = "scope-map-key")<!>
fun scopeAndMapKey() = Unit

<!CONFLICTING_ANNOTATION_ROLES!>@AllRoles(value = "all")<!>
fun allRoles() = Unit

<!CONFLICTING_ANNOTATION_ROLES!>@DaggerAndJsrRoles(value = "interop")<!>
fun daggerAndJsrRoles() = Unit

@Inject
class GeneratedAnnotationCopy(
  <!CONFLICTING_ANNOTATION_ROLES!>@ExternalQualifierAndScope<!> value: String
)

abstract class AppScope

interface ContributionRoleService

@ContributesIntoMap(AppScope::class)
<!CONFLICTING_ANNOTATION_ROLES!>@QualifierAndMapKey(value = "class")<!>
@Inject
class ConflictingClassRoleContribution : ContributionRoleService

@Inject
@ContributesIntoMap(
  AppScope::class,
  binding = binding<<!CONFLICTING_ANNOTATION_ROLES!>@QualifierAndMapKey(value = "type")<!> ContributionRoleService>(),
)
class ConflictingTypeRoleContribution : ContributionRoleService
