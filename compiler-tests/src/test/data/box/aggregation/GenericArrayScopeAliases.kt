typealias Matrix<T> = Array<Array<T>>
typealias StringMatrix = Matrix<String>
typealias PrimitiveMatrix = Array<IntArray>

@ContributesTo(StringMatrix::class)
interface ArrayScopeContribution

@ContributesTo(PrimitiveMatrix::class)
interface PrimitiveArrayScopeContribution

// Resolving generated contribution classifiers must leave the original type arguments intact.
fun box(): String {
  val matrix: StringMatrix = arrayOf(arrayOf("OK"))
  return matrix.single().single()
}
