// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity

/**
 * The most derived bindings one chain can create before lookup stops. It guards against runaway
 * derivations that the divergence check doesn't catch.
 */
public const val MAX_DERIVED_BINDING_DEPTH: Int = 64

/**
 * Where a type variable's argument sits in the target type.
 *
 * [path] lists type-argument indices to follow from the target's root. Each index is a position in
 * [TypeIdentity.Class.arguments]. An empty path means the variable binds the whole target.
 *
 * When [stripsNullability] is true, the variable binds the non-null form of that subtree. This
 * happens for a `T?` pattern matched against a nullable target.
 */
public data class TypeVariableBinding(val path: List<Int>, val stripsNullability: Boolean)

/**
 * Matches a generic provider's return type ([pattern]) against a requested key's type ([target]).
 * Returns null when they don't match. Otherwise it returns where each variable's type sits in
 * [target].
 *
 * A type parameter in [pattern] is a variable when [isVariable] returns true for it. Any other type
 * parameter is a constant and must equal the target at that position. Specificity checks use this
 * to treat another provider's type parameters as fixed types.
 *
 * Map keys always use [Nullability.NotNull], so `T` and `T?` land on the same key.
 *
 * These are the rules.
 * - Non-variable positions must be equal. Classes need the same class ID, nullability, and argument
 *   count. Each argument needs the same projection variance.
 * - A variable never binds a star projection. A star in [pattern] only matches a star in [target].
 * - Pattern `T` binds the whole target subtree, including its nullability.
 * - Pattern `T?` needs a nullable class or type parameter target. It binds the non-null form.
 * - Pattern `T & Any` needs a target that is a class or type parameter and can't hold null. A class
 *   target must be non-null. A type parameter target must be definitely non-null, since a plain `U`
 *   might still be nullable. The variable binds the target as is.
 * - A variable that occurs more than once must bind equal types each time. The result keeps the
 *   first occurrence.
 * - Flexible, intersection, and unresolved patterns only match an equal target.
 */
public fun matchTypePattern(
  pattern: TypeIdentity,
  target: TypeIdentity,
  isVariable: (TypeIdentity.TypeParameter) -> Boolean,
): Map<TypeIdentity.TypeParameter, TypeVariableBinding>? {
  val matcher = TypePatternMatcher(isVariable)
  val matched = matcher.match(pattern, target)
  if (!matched) {
    return null
  }
  return matcher.bindings.mapValues { (_, bound) -> bound.binding }
}

/**
 * Walks a pattern and a target in step and collects what each variable stands for.
 *
 * It visits each pattern node once and never backtracks. It doesn't walk the target below a
 * variable. One instance handles one match.
 */
private class TypePatternMatcher(private val isVariable: (TypeIdentity.TypeParameter) -> Boolean) {
  /** Each variable's binding plus the type it binds, for comparing repeated occurrences. */
  class BoundVariable(val binding: TypeVariableBinding, val type: TypeIdentity)

  /** What each variable stands for so far. Keys are the variables' non-null forms. */
  val bindings = LinkedHashMap<TypeIdentity.TypeParameter, BoundVariable>()

  /** Argument indices from the target's root down to the position being matched. */
  private val path = ArrayList<Int>()

  fun match(pattern: TypeIdentity, target: TypeIdentity): Boolean {
    return when (pattern) {
      is TypeIdentity.TypeParameter -> {
        if (isVariable(pattern)) {
          bindVariable(pattern, target)
        } else {
          // Another declaration's type parameter is a fixed type here.
          pattern == target
        }
      }
      is TypeIdentity.Class -> matchClass(pattern, target)
      // The matcher doesn't look inside these, so they only match an equal target.
      is TypeIdentity.Flexible,
      is TypeIdentity.Intersection,
      is TypeIdentity.Unresolved -> pattern == target
    }
  }

  private fun matchClass(pattern: TypeIdentity.Class, target: TypeIdentity): Boolean {
    if (target !is TypeIdentity.Class) {
      return false
    }
    // The cheap comparisons run before the class ID string comparison.
    if (pattern.nullability != target.nullability) {
      return false
    }
    if (pattern.arguments.size != target.arguments.size) {
      return false
    }
    // Nested classes need this even though lookups already index candidates by root class ID.
    if (pattern.classId != target.classId) {
      return false
    }
    for (index in pattern.arguments.indices) {
      // The path is shared and only copied when a variable binds.
      path.add(index)
      val matched = matchArgument(pattern.arguments[index], target.arguments[index])
      path.removeAt(path.lastIndex)
      if (!matched) {
        return false
      }
    }
    return true
  }

  private fun matchArgument(pattern: TypeArgumentIdentity, target: TypeArgumentIdentity): Boolean {
    return when (pattern) {
      // A star has no type for a variable to bind, so it only matches another star.
      TypeArgumentIdentity.Star -> target == TypeArgumentIdentity.Star
      is TypeArgumentIdentity.Projection -> {
        if (target !is TypeArgumentIdentity.Projection) {
          return false
        }
        // Keys that differ in variance are different keys.
        if (pattern.variance != target.variance) {
          return false
        }
        match(pattern.type, target.type)
      }
    }
  }

  /**
   * Records that [variable] stands for [target] at the current position. Returns false when this
   * occurrence can't bind [target] or disagrees with an earlier occurrence.
   *
   * How the pattern writes the variable decides what it binds. A plain `T` takes [target] as is.
   * `T?` needs a nullable [target] and takes its non-null form. `T & Any` needs a [target] that
   * can't hold null and takes it as is.
   */
  private fun bindVariable(variable: TypeIdentity.TypeParameter, target: TypeIdentity): Boolean {
    // The type this occurrence says the variable stands for.
    val boundType =
      when (variable.nullability) {
        Nullability.NotNull -> target
        Nullability.Nullable -> {
          val nonNullTarget = nonNullFormOfNullable(target)
          if (nonNullTarget == null) {
            return false
          }
          nonNullTarget
        }
        Nullability.DefinitelyNotNull -> {
          if (!canNeverBeNull(target)) {
            return false
          }
          target
        }
      }
    // `T`, `T?`, and `T & Any` are one variable, so they share one entry.
    val key = variable.copy(nullability = Nullability.NotNull)
    val earlier = bindings[key]
    if (earlier != null) {
      // A repeated variable keeps its first binding, and later occurrences must agree with it.
      return earlier.type == boundType
    }
    val stripsNullability = variable.nullability == Nullability.Nullable
    bindings[key] = BoundVariable(TypeVariableBinding(path.toList(), stripsNullability), boundType)
    return true
  }
}

/** The non-null form of a nullable class or type parameter, or null for any other target. */
private fun nonNullFormOfNullable(target: TypeIdentity): TypeIdentity? {
  return when (target) {
    is TypeIdentity.Class -> {
      if (target.nullability == Nullability.Nullable) {
        target.copy(nullability = Nullability.NotNull)
      } else {
        null
      }
    }
    is TypeIdentity.TypeParameter -> {
      if (target.nullability == Nullability.Nullable) {
        target.copy(nullability = Nullability.NotNull)
      } else {
        null
      }
    }
    else -> null
  }
}

/** Whether [target] is a class or type parameter that can't hold null. */
private fun canNeverBeNull(target: TypeIdentity): Boolean {
  return when (target) {
    is TypeIdentity.Class -> target.nullability != Nullability.Nullable
    is TypeIdentity.TypeParameter -> target.nullability == Nullability.DefinitelyNotNull
    else -> false
  }
}

/** The result of picking among generic providers that all matched a key. */
public sealed interface DerivedCandidateSelection<out C> {
  /** No provider matched. */
  public data object None : DerivedCandidateSelection<Nothing>

  /** Exactly one provider matched. */
  public data class Single<out C>(val candidate: C) : DerivedCandidateSelection<C>

  /** Several providers matched and [winner] is more specific than each of [others]. */
  public data class MostSpecific<out C>(val winner: C, val others: List<C>) :
    DerivedCandidateSelection<C>

  /** Several providers matched and none is more specific than all the rest. */
  public data class Ambiguous<out C>(val candidates: List<C>) : DerivedCandidateSelection<C>
}

/**
 * Picks the most specific of [candidates], which must all have matched the same key.
 *
 * [patternOf] returns a candidate's return type. [isVariableOf] tells whether a type parameter is
 * one of that candidate's own variables.
 *
 * Candidate A is at least as specific as B when B's pattern matches A's pattern. In that match only
 * B's type parameters are variables, and A's type parameters are constants. So `Encoder<List<T>>`
 * is at least as specific as `Encoder<T>`, and the reverse is false.
 *
 * A candidate wins when it's at least as specific as every other candidate and no other candidate
 * is at least as specific as it. Results keep the order of [candidates].
 */
public fun <C : Any> selectMostSpecificCandidate(
  candidates: List<C>,
  patternOf: (C) -> TypeIdentity,
  isVariableOf: (C, TypeIdentity.TypeParameter) -> Boolean,
): DerivedCandidateSelection<C> {
  when (candidates.size) {
    0 -> return DerivedCandidateSelection.None
    1 -> return DerivedCandidateSelection.Single(candidates.single())
  }

  fun isAtLeastAsSpecific(candidate: C, other: C): Boolean {
    val match =
      matchTypePattern(
        pattern = patternOf(other),
        target = patternOf(candidate),
        isVariable = { isVariableOf(other, it) },
      )
    return match != null
  }

  for (winnerIndex in candidates.indices) {
    val winner = candidates[winnerIndex]
    val beatsEveryOther =
      candidates.indices.all { otherIndex ->
        if (otherIndex == winnerIndex) {
          return@all true
        }
        val other = candidates[otherIndex]
        val coversOther = isAtLeastAsSpecific(winner, other)
        coversOther && !isAtLeastAsSpecific(other, winner)
      }
    if (beatsEveryOther) {
      val others = candidates.filterIndexed { index, _ -> index != winnerIndex }
      return DerivedCandidateSelection.MostSpecific(winner, others)
    }
  }
  return DerivedCandidateSelection.Ambiguous(candidates)
}

/**
 * Whether deriving [newKey] diverges. This follows Scala's implicit divergence rule.
 *
 * [earlierKeysFromSameProvider] are the keys earlier in the same derivation chain that the same
 * provider created. Derivation diverges when one of them mentions the same class IDs as [newKey]
 * and has a smaller [derivationNodeCount]. For example, `Encoder<List<List<String>>>` diverges
 * after `Encoder<List<String>>`. It doesn't diverge after `Encoder<List<User>>` because the class
 * IDs differ.
 */
public fun isDivergentDerivation(
  newKey: TypeIdentity,
  earlierKeysFromSameProvider: List<TypeIdentity>,
): Boolean {
  if (earlierKeysFromSameProvider.isEmpty()) {
    return false
  }
  val newClassIds = derivationClassIds(newKey)
  val newNodeCount = derivationNodeCount(newKey)
  return earlierKeysFromSameProvider.any { earlier ->
    // Counting nodes allocates nothing, so it runs before the class ID sets are compared.
    val isSmaller = derivationNodeCount(earlier) < newNodeCount
    isSmaller && derivationClassIds(earlier) == newClassIds
  }
}

/**
 * Every class ID that appears anywhere in [type]. Type parameters and unresolved types add nothing.
 */
public fun derivationClassIds(type: TypeIdentity): Set<String> {
  val classIds = mutableSetOf<String>()
  fun collect(node: TypeIdentity) {
    when (node) {
      is TypeIdentity.Class -> {
        classIds += node.classId
        for (argument in node.arguments) {
          if (argument is TypeArgumentIdentity.Projection) {
            collect(argument.type)
          }
        }
      }
      is TypeIdentity.Flexible -> {
        collect(node.lower)
        collect(node.upper)
      }
      is TypeIdentity.Intersection -> node.types.forEach(::collect)
      // A type parameter's identity leaves out its bounds, so both of these are leaves.
      is TypeIdentity.TypeParameter,
      is TypeIdentity.Unresolved -> {}
    }
  }
  collect(type)
  return classIds
}

/**
 * The number of type nodes in [type]. Each class, type parameter, flexible type, intersection,
 * unresolved type, and star projection counts as one node. A projection adds only its type's nodes.
 * Nullability doesn't change the count. So `Encoder<List<String>>` has 3 nodes and `Map<*, T?>`
 * has 3.
 */
public fun derivationNodeCount(type: TypeIdentity): Int {
  return when (type) {
    is TypeIdentity.Class ->
      1 +
        type.arguments.sumOf { argument ->
          when (argument) {
            TypeArgumentIdentity.Star -> 1
            is TypeArgumentIdentity.Projection -> derivationNodeCount(argument.type)
          }
        }
    is TypeIdentity.Flexible ->
      1 + derivationNodeCount(type.lower) + derivationNodeCount(type.upper)
    is TypeIdentity.Intersection -> 1 + type.types.sumOf(::derivationNodeCount)
    is TypeIdentity.TypeParameter,
    is TypeIdentity.Unresolved -> 1
  }
}
