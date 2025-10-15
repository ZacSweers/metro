// RENDER_DIAGNOSTICS_FULL_TEXT

<!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
annotation class AnnotationContainer

<!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
enum class EnumContainer {
  INSTANCE
}

class CompanionObjectContainer {
  <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
  companion object
}

<!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
sealed class SealedClass

<!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
sealed interface SealedInterface

class Containing {
  <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
  inner class InnerClass
}
