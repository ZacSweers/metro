// RENDER_DIAGNOSTICS_FULL_TEXT

// Final class - not allowed
<!DEFAULT_BINDING_ERROR!>@DefaultBinding<FinalClass><!>
class FinalClass

// Object - not allowed
<!DEFAULT_BINDING_ERROR!>@DefaultBinding<Singleton><!>
object Singleton

// Enum - not allowed
<!DEFAULT_BINDING_ERROR!>@DefaultBinding<MyEnum><!>
enum class MyEnum { A, B }

// Interface - allowed
@DefaultBinding<ValidInterface>
interface ValidInterface

// Abstract class - allowed
@DefaultBinding<ValidAbstract>
abstract class ValidAbstract

// Open class - allowed
@DefaultBinding<ValidOpen>
open class ValidOpen

// Sealed class - allowed
@DefaultBinding<ValidOpen>
sealed class ValidSealed
