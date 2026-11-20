// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Multibinds fun <!MULTIBINDS_ERROR!>missingReturnType<!>()
  @Multibinds fun explicitBadReturn(): <!MULTIBINDS_ERROR!>Nothing<!>
  @Multibinds fun badMapSubtype(): <!MULTIBINDS_ERROR!>LinkedHashMap<String, String><!>
  @Multibinds fun badSetSubtype(): <!MULTIBINDS_ERROR!>LinkedHashSet<String><!>
  @Multibinds fun okMap(): Map<String, String>
  @Multibinds fun okSet(): Set<String>
}
