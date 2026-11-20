// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@Inject
open class OpenExampleClass

@Inject
<!ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED!>sealed<!> class SealedExampleClass

@Inject
<!ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED!>abstract<!> class AbstractExampleClass

@Inject
class HappyClass
