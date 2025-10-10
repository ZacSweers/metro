// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @Provides
  fun provideString(@OptionalDependency <!OPTIONAL_DEPENDENCY_WARNING!>value<!>: Int = 3): String = value.toString()
}

@Inject
class Example(<!ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD!>@OptionalDependency<!> val <!OPTIONAL_DEPENDENCY_WARNING!>value<!>: Int = 3)
