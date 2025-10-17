// RENDER_DIAGNOSTICS_FULL_TEXT

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@BindingContainer
class InjectedFromConstructorParameter(
  <!BINDING_CONTAINER_ERROR!>@Provides<!> val default: String,
  <!BINDING_CONTAINER_ERROR!>@param:Provides<!> val parameter: String,
  <!BINDING_CONTAINER_ERROR!>@get:Provides<!> val getter: Int,
  <!BINDING_CONTAINER_ERROR!>@property:Provides<!> val property: Long,
  <!BINDING_CONTAINER_ERROR!>@field:Provides<!> val field: Long,
)
