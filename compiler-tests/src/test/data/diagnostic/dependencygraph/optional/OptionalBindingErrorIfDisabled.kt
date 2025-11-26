// RENDER_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DISABLED

@DependencyGraph
interface AppGraph {
  @Provides
  fun provideString(@OptionalBinding <!OPTIONAL_BINDING_ERROR!>value<!>: Int = 3): String = value.toString()
}

@Inject
class Example(<!ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD!>@OptionalBinding<!> val <!OPTIONAL_BINDING_ERROR!>value<!>: Int = 3)
