// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@Inject
<!INJECTED_CLASSES_MUST_BE_VISIBLE!>private<!> class PrivateClass

open class Example {
  @Inject
  <!INJECTED_CLASSES_MUST_BE_VISIBLE!>protected<!> class ProtectedClass
}

@Inject
internal class HappyInternalClass

@Inject
public class HappyPublicClass
