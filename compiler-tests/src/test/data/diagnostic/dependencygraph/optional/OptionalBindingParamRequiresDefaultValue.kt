// RENDER_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_BINDING

@DependencyGraph
interface AppGraph {
  @Provides
  fun provideString(@OptionalBinding value: <!OPTIONAL_BINDING_ERROR!>Int<!>): String = value.toString()
}

@Inject
class Example(<!ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD!>@OptionalBinding<!> val value: <!OPTIONAL_BINDING_ERROR!>Int<!>)
