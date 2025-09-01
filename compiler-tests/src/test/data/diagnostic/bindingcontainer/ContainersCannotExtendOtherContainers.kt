// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
interface AnotherContainer

@BindingContainer
interface ConfusedContainer : <!BINDING_CONTAINER_ERROR!>AnotherContainer<!>

interface Intermediate : AnotherContainer

@BindingContainer
interface <!BINDING_CONTAINER_ERROR!>ConfusedContainer2<!> : Intermediate
