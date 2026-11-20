// RENDER_DIAGNOSTICS_FULL_TEXT

package test

enum class EnumClass {
  INSTANCE;
  @Inject fun <!MEMBERS_INJECT_ERROR!>intFunction<!>(int: Int) {

  }
}
object ObjectClass {
  @Inject fun <!MEMBERS_INJECT_ERROR!>intFunction<!>(int: Int) {

  }
}
interface InterfaceClass {
  @Inject fun <!MEMBERS_INJECT_ERROR!>intFunction<!>(int: Int)
}
