// RENDER_DIAGNOSTICS_FULL_TEXT

// final class with members doesn't require the annotation
class FinalClassWithMembers {
  @Inject lateinit var injected: String
}

@HasMemberInjections
abstract class AnnotatedParent {
  @Inject lateinit var parentInjected: String
}

// final class with no members but injected parent doesn't require the annotation
class FinalClassWithAnnotatedParent : AnnotatedParent()

// open class with members requires it
open class <!MEMBERS_INJECT_ERROR!>OpenClass<!> {
  @Inject lateinit var injected: String
}

// open class with no members but injected parent requires it
open class <!MEMBERS_INJECT_ERROR!>OpenClassWithAnnotatedParent<!> : AnnotatedParent()

// sealed versions
sealed class <!MEMBERS_INJECT_ERROR!>SealedClass<!> {
  @Inject lateinit var injected: String
}
sealed class <!MEMBERS_INJECT_ERROR!>SealedClassWithAnnotatedParent<!> : AnnotatedParent()

// open versions
abstract class <!MEMBERS_INJECT_ERROR!>AbstractClass<!> {
  @Inject lateinit var injected: String
}
abstract class <!MEMBERS_INJECT_ERROR!>AbstractClassWithAnnotatedParent<!> : AnnotatedParent()

// unnecessary annotation
<!MEMBERS_INJECT_ERROR!>@HasMemberInjections<!>
class UnnecessaryFinalDecl

<!MEMBERS_INJECT_ERROR!>@HasMemberInjections<!>
open class UnnecessaryOpenDecl
