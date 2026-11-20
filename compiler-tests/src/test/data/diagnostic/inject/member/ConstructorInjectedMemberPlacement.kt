// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT

@Inject
class NonNullMembers {
  @Inject lateinit var <!MEMBERS_INJECT_WARNING!>text<!>: String
  @Inject var <!MEMBERS_INJECT_WARNING!>primitive<!>: Int = 0
}

@Inject
class NullableMember {
  @Inject var nullable: Int? = 0
}
