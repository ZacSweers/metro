// Regression test for https://github.com/ZacSweers/metro/issues/1097

@DependencyGraph(AppScope::class)
interface ParameterizedGraph {
  val myProvider: BitmapMyProvider?
  val parameterizedMyProvider: ParameterizedMyProvider<Bitmap>?
}

interface ParameterizedMyProvider<T> : () -> T
fun interface BitmapMyProvider {
  operator fun invoke(): Bitmap?
}

@Inject
@ContributesBinding(AppScope::class)
// nullable binding compiles fine
@ContributesBinding(AppScope::class, binding = binding<BitmapMyProvider?>())
class MaybeBitmapMyProvider : BitmapMyProvider {
  override fun invoke(): Bitmap {
    return TODO()
  }
}

class Bitmap

@Inject
@ContributesBinding(AppScope::class)
// duplicate binding error
// compiles and correctly binds to the nullable type if removing this binding
@ContributesBinding(AppScope::class, binding = binding<ParameterizedMyProvider<Bitmap>?>())
class MaybeBitmapParameterizedMyProvider : ParameterizedMyProvider<Bitmap> {
  override fun invoke(): Bitmap {
    TODO()
  }
}

fun box(): String {
  // Compile-only test
  return "OK"
}