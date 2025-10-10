// RENDER_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_DEPENDENCY

@DependencyGraph
interface AppGraph {
  @Provides
  fun provideString(@OptionalDependency value: <!OPTIONAL_DEPENDENCY_ERROR!>Int<!>): String = value.toString()
}

@Inject
class Example(<!ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD!>@OptionalDependency<!> val value: <!OPTIONAL_DEPENDENCY_ERROR!>Int<!>)
