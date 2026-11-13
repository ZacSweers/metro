// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.graph.DerivedBindingDiagnosticMessages
import dev.zacsweers.metro.compiler.graph.TypeVariableBinding
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.identity.AnnotationIdentity
import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.mapTypes
import dev.zacsweers.metro.compiler.graph.matchTypePattern
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance

/**
 * One type parameter of a generic `@Provides` or `@Binds` declaration.
 *
 * [identity] is the non-null form that occurrences in the declaration's key and dependencies use.
 * [upperBounds] are its declared bounds. They can mention the declaration's other type parameters.
 */
internal class GenericTypeParameter(
  val name: String,
  val identity: TypeIdentity.TypeParameter,
  val upperBounds: List<KaTypeSnapshot>,
)

/**
 * The session-free view of a generic provider or binds declaration that the derived stage needs.
 *
 * The declaration's [KaBinding.typeKey] is the pattern. Its type parameters are the variables.
 * [providerName] is the fully qualified function name that diagnostics show. [isEnabled] is false
 * when the declaring source module disables derived bindings. The compiler plugin's own FIR check
 * flags those declarations, so resolution ignores them.
 */
internal class GenericBindingSignature(
  val typeParameters: List<GenericTypeParameter>,
  val providerName: String,
  val isEnabled: Boolean,
) {
  private val variables: Set<TypeIdentity.TypeParameter> =
    typeParameters.mapTo(HashSet()) { it.identity }

  /** Whether [typeParameter] is one of this declaration's own type parameters. */
  fun isVariable(typeParameter: TypeIdentity.TypeParameter): Boolean {
    return typeParameter.copy(nullability = Nullability.NotNull) in variables
  }

  /** Whether [type] mentions any of this declaration's own type parameters. */
  fun isMentionedIn(type: TypeIdentity): Boolean = type.mentionsAny(variables)
}

/**
 * Where a derived binding came from. [template] is the generic declaration. [typeArguments] holds
 * the inferred argument for each of its type parameters, in declaration order.
 *
 * [boundsVerified] is false when a bound couldn't be checked without an analysis session. See
 * [matchGenericBinding].
 */
internal class BindingDerivation(
  val template: KaBinding,
  val typeArguments: List<KaTypeSnapshot>,
  val boundsVerified: Boolean,
) {
  val signature: GenericBindingSignature
    get() = checkNotNull(template.genericSignature) { "Derivation from a non-generic binding" }

  /** Each type parameter's name paired with its rendered argument, in declaration order. */
  fun renderedTypeArguments(short: Boolean): List<Pair<String, String>> {
    return signature.typeParameters.zip(typeArguments) { parameter, argument ->
      val rendered =
        if (short) {
          argument.shortType
        } else {
          argument.renderedType
        }
      parameter.name to rendered
    }
  }

  /** The sentence binding explanations show for a derived binding. */
  fun explanationDetail(): String {
    return DerivedBindingDiagnosticMessages.derivedBindingDetail(
      provider = signature.providerName,
      typeArguments = renderedTypeArguments(short = false),
    )
  }

  /** The note diagnostic traces show for a derived binding with [key]. */
  fun notes(key: KaTypeKey): List<Note> {
    val text =
      DerivedBindingDiagnosticMessages.derivedBindingNote(
        key = key.render(short = true),
        provider = signature.providerName,
        typeArguments = renderedTypeArguments(short = true),
      )
    return listOf(Note.note(text))
  }
}

/** The indexed declaration behind this binding. A derived binding maps to its generic template. */
internal fun KaBinding.declarationBinding(): KaBinding = derivation?.template ?: this

/** Generic declarations are indexed by the raw class and qualifier of the key they provide. */
internal data class GenericBindingLookupKey(
  val classId: ClassId,
  val qualifier: AnnotationIdentity?,
)

internal fun KaTypeKey.genericLookupKey(): GenericBindingLookupKey? {
  val classId = type.classId ?: return null
  return GenericBindingLookupKey(classId, qualifier?.identity)
}

/**
 * Matches [template]'s pattern against [key] and builds the derived binding for it. Returns null
 * when the pattern doesn't match, a bound fails, or a dependency can't be substituted.
 *
 * Matching, nullability handling, and argument paths come from the shared [matchTypePattern], so
 * they follow the compiler exactly.
 *
 * Bounds are checked without an analysis session, since the index outlives its sessions. The check
 * reads the [KaTypeHierarchy] that requested keys and bounds record while a session is open. That
 * is enough for class bounds like `Number`, bounds that mention a type parameter like
 * `Comparable<T>`, and variant type arguments like `List<Int>` for a bound of `List<Number>`.
 *
 * Sometimes the check can't tell whether an argument fits a bound. The declaration still matches
 * then, and the derivation gets [BindingDerivation.boundsVerified] set to false. Rejecting it would
 * report a missing binding for code that may be valid. This happens when a key came from a module
 * that doesn't enable derived bindings, so nothing was recorded for it. It also happens for
 * flexible types, for projected type arguments, and for type arguments that only appear in a
 * supertype.
 */
internal fun matchGenericBinding(template: KaBinding, key: KaTypeKey): KaBinding? {
  val signature = template.genericSignature ?: return null
  val inferred = inferTypeArguments(template, signature, key) ?: return null
  val bounds = checkBounds(signature, inferred)
  if (bounds.unsatisfied != null) {
    return null
  }
  val derivation = BindingDerivation(template, inferred.arguments, bounds.allVerified)
  return template.instantiate(key, inferred.substitutions, derivation)
}

/**
 * Returns the bound that keeps [template] from providing [key], or null. It's null when the pattern
 * doesn't match [key], and when every bound holds or can't be checked. A missing-binding report
 * uses it to explain the near miss, like the compiler does.
 */
internal fun unsatisfiedBoundFor(template: KaBinding, key: KaTypeKey): UnsatisfiedBound? {
  val signature = template.genericSignature ?: return null
  val inferred = inferTypeArguments(template, signature, key) ?: return null
  return checkBounds(signature, inferred).unsatisfied
}

/**
 * An inferred type argument that breaks a bound. [bound] has the declaration's type arguments
 * substituted, so `B : A` with `A = Number` reads as `Number`.
 */
internal class UnsatisfiedBound(
  val typeParameter: GenericTypeParameter,
  val argument: KaTypeSnapshot,
  val bound: KaTypeSnapshot,
)

/**
 * The type arguments a generic declaration's pattern gives for one key. [arguments] are in type
 * parameter order. [substitutions] maps each type parameter to its argument.
 */
private class InferredTypeArguments(
  val arguments: List<KaTypeSnapshot>,
  val substitutions: Map<TypeIdentity.TypeParameter, KaTypeSnapshot>,
)

/**
 * Reads [template]'s type arguments out of [key]. It doesn't check bounds. Returns null when the
 * pattern doesn't match [key].
 */
private fun inferTypeArguments(
  template: KaBinding,
  signature: GenericBindingSignature,
  key: KaTypeKey,
): InferredTypeArguments? {
  val variableBindings =
    matchTypePattern(
      pattern = template.typeKey.type.identity,
      target = key.type.identity,
      isVariable = signature::isVariable,
    ) ?: return null

  val arguments = ArrayList<KaTypeSnapshot>(signature.typeParameters.size)
  for (parameter in signature.typeParameters) {
    val binding = variableBindings[parameter.identity] ?: return null
    val argument = key.type.typeArgumentAt(binding.path) ?: return null
    if (binding.stripsNullability) {
      // The return type wrote `T?` here, so `T` is the non-null form.
      arguments += argument.withNullability(nullable = false) ?: return null
    } else {
      arguments += argument
    }
  }

  val substitutions = HashMap<TypeIdentity.TypeParameter, KaTypeSnapshot>()
  for ((index, parameter) in signature.typeParameters.withIndex()) {
    substitutions[parameter.identity] = arguments[index]
  }
  return InferredTypeArguments(arguments, substitutions)
}

/**
 * What checking every bound found. [unsatisfied] is the first bound that failed. [allVerified] is
 * false when a bound couldn't be checked.
 */
private class BoundsResult(val unsatisfied: UnsatisfiedBound?, val allVerified: Boolean)

/** Checks each inferred argument against its type parameter's bounds, and stops at a failure. */
private fun checkBounds(
  signature: GenericBindingSignature,
  inferred: InferredTypeArguments,
): BoundsResult {
  var allVerified = true
  for ((index, parameter) in signature.typeParameters.withIndex()) {
    val argument = inferred.arguments[index]
    for (bound in parameter.upperBounds) {
      // A bound can mention the declaration's type parameters, like `B : A` or `T : Comparable<T>`.
      val substitutedBound = bound.substitute(inferred.substitutions)
      if (substitutedBound == null) {
        allVerified = false
        continue
      }
      when (checkBound(argument, substitutedBound)) {
        BoundCheck.HOLDS -> {}
        BoundCheck.FAILS -> {
          val unsatisfied = UnsatisfiedBound(parameter, argument, substitutedBound)
          return BoundsResult(unsatisfied, allVerified)
        }
        BoundCheck.UNKNOWN -> allVerified = false
      }
    }
  }
  return BoundsResult(unsatisfied = null, allVerified = allVerified)
}

/** Builds the ordinary binding a generic declaration provides for one concrete [key]. */
private fun KaBinding.instantiate(
  key: KaTypeKey,
  substitutions: Map<TypeIdentity.TypeParameter, KaTypeSnapshot>,
  derivation: BindingDerivation,
): KaBinding? {
  return when (this) {
    is KaBinding.Provided -> {
      val dependencies = ArrayList<KaContextualTypeKey>(this.dependencies.size)
      for (dependency in this.dependencies) {
        dependencies += dependency.substitute(substitutions) ?: return null
      }
      KaBinding.Provided(
        pointer = pointer,
        typeKey = key,
        scope = scope,
        implementationName = implementationName,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        ownerGraphId = ownerGraphId,
        includedContainerKey = includedContainerKey,
        replaces = replaces,
        contributionScopes = contributionScopes,
        priority = priority,
        priorityFromAnvilRank = priorityFromAnvilRank,
        isClassContribution = isClassContribution,
        dependencies = dependencies,
        memberInjectionOwnerIds = memberInjectionOwnerIds,
        isSuspend = isSuspend,
        hintAvailability = hintAvailability,
        isGraphPrivate = isGraphPrivate,
        derivation = derivation,
      )
    }
    is KaBinding.Alias -> {
      val consumed = consumedKey
      val substitutedConsumed =
        if (consumed == null) {
          null
        } else {
          consumed.substitute(substitutions) ?: return null
        }
      KaBinding.Alias(
        pointer = pointer,
        typeKey = key,
        consumedKey = substitutedConsumed,
        scope = scope,
        implementationName = implementationName,
        mapKeyValue = mapKeyValue,
        originClassId = originClassId,
        containerId = containerId,
        ownerGraphId = ownerGraphId,
        includedContainerKey = includedContainerKey,
        replaces = replaces,
        contributionScopes = contributionScopes,
        priority = priority,
        priorityFromAnvilRank = priorityFromAnvilRank,
        isClassContribution = isClassContribution,
        hintAvailability = hintAvailability,
        isGraphPrivate = isGraphPrivate,
        derivation = derivation,
      )
    }
    else -> null
  }
}

/**
 * Whether [other] provides the same keys as [this]. Their qualifiers match and each pattern matches
 * the other with only its own type parameters as variables. Type parameters in the same place also
 * have the same bounds.
 *
 * So `<T> Encoder<List<T>>` is alpha-equivalent to `<E> Encoder<List<E>>`. It isn't
 * alpha-equivalent to `<E : Number> Encoder<List<E>>`, which provides fewer keys.
 */
internal fun KaBinding.isAlphaEquivalentTo(other: KaBinding): Boolean {
  val signature = genericSignature ?: return false
  val otherSignature = other.genericSignature ?: return false
  if (typeKey.qualifier?.identity != other.typeKey.qualifier?.identity) {
    return false
  }
  val pattern = typeKey.type.identity
  val otherPattern = other.typeKey.type.identity
  val otherVariables = matchTypePattern(otherPattern, pattern, otherSignature::isVariable)
  if (otherVariables == null) {
    return false
  }
  val thisCoversOther = matchTypePattern(pattern, otherPattern, signature::isVariable) != null
  if (!thisCoversOther) {
    return false
  }
  return hasSameBoundsAs(signature, otherSignature, otherVariables)
}

/**
 * Whether each of [otherSignature]'s type parameters has the same bounds as the type parameter of
 * [signature] in the same place. [otherVariables] says where each of the other's type parameters
 * sits in this binding's pattern.
 *
 * A bound that can't be rebuilt with the renamed type parameters is skipped. The two type
 * parameters are then treated as having the same bounds.
 */
private fun KaBinding.hasSameBoundsAs(
  signature: GenericBindingSignature,
  otherSignature: GenericBindingSignature,
  otherVariables: Map<TypeIdentity.TypeParameter, TypeVariableBinding>,
): Boolean {
  // Find this binding's type parameter that sits where each of the other's does.
  val counterparts = ArrayList<GenericTypeParameter>(otherSignature.typeParameters.size)
  // Renaming the other's type parameters to this binding's lets the bounds compare directly.
  val renames = HashMap<TypeIdentity.TypeParameter, KaTypeSnapshot>()
  for (otherParameter in otherSignature.typeParameters) {
    val binding = otherVariables[otherParameter.identity] ?: return false
    val counterpartType = typeKey.type.typeArgumentAt(binding.path) ?: return false
    val counterpartIdentity = counterpartType.identity as? TypeIdentity.TypeParameter
    if (counterpartIdentity == null) {
      return false
    }
    val nonNullIdentity = counterpartIdentity.copy(nullability = Nullability.NotNull)
    val counterpart = signature.typeParameters.firstOrNull { it.identity == nonNullIdentity }
    if (counterpart == null) {
      return false
    }
    counterparts += counterpart
    renames[otherParameter.identity] =
      counterpartType.withNullability(nullable = false) ?: return false
  }

  for ((index, otherParameter) in otherSignature.typeParameters.withIndex()) {
    val renamedBounds = otherParameter.upperBounds.map { bound -> bound.substitute(renames) }
    val canRebuildBounds = renamedBounds.none { it == null }
    if (!canRebuildBounds) {
      continue
    }
    val otherBounds = renamedBounds.boundIdentities()
    val bounds = counterparts[index].upperBounds.boundIdentities()
    if (otherBounds != bounds) {
      return false
    }
  }
  return true
}

/**
 * The identities of these bounds, without `Any?`. An unbounded type parameter and one that declares
 * `Any?` accept the same types.
 */
private fun List<KaTypeSnapshot?>.boundIdentities(): Set<TypeIdentity> {
  val identities = HashSet<TypeIdentity>()
  for (bound in this) {
    val identity = bound?.identity ?: continue
    val isAny = identity is TypeIdentity.Class && identity.classId == ANY_CLASS_ID
    val isNullableAny = isAny && identity.nullability() == Nullability.Nullable
    if (!isNullableAny) {
      identities += identity
    }
  }
  return identities
}

private enum class BoundCheck {
  HOLDS,
  FAILS,
  UNKNOWN,
}

/**
 * Decides `argument <: bound` from what the snapshots recorded. Returns UNKNOWN when a step needs a
 * [KaTypeHierarchy] that wasn't recorded, or meets a type that isn't a plain class type.
 */
private fun checkBound(argument: KaTypeSnapshot, bound: KaTypeSnapshot): BoundCheck {
  val argumentIdentity = argument.identity
  val boundIdentity = bound.identity
  val argumentIsNullable = argumentIdentity.nullability() == Nullability.Nullable
  val boundIsNullable = boundIdentity.nullability() == Nullability.Nullable
  val boundIsNonNullClass = boundIdentity is TypeIdentity.Class && !boundIsNullable
  if (argumentIsNullable && boundIsNonNullClass) {
    return BoundCheck.FAILS
  }
  val isAnyBound = boundIdentity is TypeIdentity.Class && boundIdentity.classId == ANY_CLASS_ID
  if (isAnyBound) {
    return BoundCheck.HOLDS
  }
  val isNothingArgument =
    argumentIdentity is TypeIdentity.Class && argumentIdentity.classId == NOTHING_CLASS_ID
  if (isNothingArgument) {
    return BoundCheck.HOLDS
  }
  val sameIgnoringNullability =
    argumentIdentity.withNullability(Nullability.NotNull) ==
      boundIdentity.withNullability(Nullability.NotNull)
  if (sameIgnoringNullability) {
    return BoundCheck.HOLDS
  }
  if (argumentIdentity !is TypeIdentity.Class || boundIdentity !is TypeIdentity.Class) {
    return BoundCheck.UNKNOWN
  }
  // Nullability is settled above. What's left is the class hierarchy and the type arguments.
  val asBoundClass = argument.asClass(boundIdentity.classId)
  return when (asBoundClass) {
    ClassView.Unknown -> BoundCheck.UNKNOWN
    ClassView.Missing -> BoundCheck.FAILS
    is ClassView.Found -> checkTypeArguments(asBoundClass.type, bound)
  }
}

/** How a type looks as one of the classes in its hierarchy. */
private sealed interface ClassView {
  /** The type itself or the supertype with that class. */
  class Found(val type: KaTypeSnapshot) : ClassView

  /** The class isn't in the type's hierarchy. */
  data object Missing : ClassView

  /** The type's supertypes weren't recorded or didn't all resolve. */
  data object Unknown : ClassView
}

/** Finds this type or the supertype of it whose class is [classId]. */
private fun KaTypeSnapshot.asClass(classId: String): ClassView {
  val ownClassId = (identity as? TypeIdentity.Class)?.classId
  if (ownClassId == classId) {
    return ClassView.Found(this)
  }
  val supertypes = hierarchy?.supertypes ?: return ClassView.Unknown
  var allAreKnownClasses = true
  for (supertype in supertypes) {
    val supertypeIdentity = supertype.identity
    val isKnownClass = supertypeIdentity is TypeIdentity.Class && !supertype.isError
    if (!isKnownClass) {
      // An unresolved or flexible supertype could be the class, so its absence proves nothing.
      allAreKnownClasses = false
      continue
    }
    if (supertypeIdentity.classId == classId) {
      return ClassView.Found(supertype)
    }
  }
  return if (allAreKnownClasses) {
    ClassView.Missing
  } else {
    ClassView.Unknown
  }
}

/** Checks the type arguments of [type] against [bound]'s. Both have the same class. */
private fun checkTypeArguments(type: KaTypeSnapshot, bound: KaTypeSnapshot): BoundCheck {
  val arguments = type.typeArguments
  val boundArguments = bound.typeArguments
  if (arguments.size != boundArguments.size) {
    return BoundCheck.UNKNOWN
  }
  val declaredVariances = bound.hierarchy?.parameterVariances
  var result = BoundCheck.HOLDS
  for (index in boundArguments.indices) {
    val declaredVariance = declaredVariances?.getOrNull(index)
    when (checkTypeArgument(arguments[index], boundArguments[index], declaredVariance)) {
      BoundCheck.HOLDS -> {}
      BoundCheck.FAILS -> return BoundCheck.FAILS
      BoundCheck.UNKNOWN -> result = BoundCheck.UNKNOWN
    }
  }
  return result
}

/**
 * Checks one type argument against the bound's argument in the same position. [declaredVariance] is
 * the variance the class declares for that type parameter, or null when it wasn't recorded.
 */
private fun checkTypeArgument(
  argument: KaTypeArgumentSnapshot,
  boundArgument: KaTypeArgumentSnapshot,
  declaredVariance: Variance?,
): BoundCheck {
  // A star in the bound accepts any argument.
  if (boundArgument !is KaTypeArgumentSnapshot.Typed) {
    return BoundCheck.HOLDS
  }
  if (argument !is KaTypeArgumentSnapshot.Typed) {
    return BoundCheck.UNKNOWN
  }
  if (argument == boundArgument) {
    return BoundCheck.HOLDS
  }
  // A projection on the argument itself needs capture rules the snapshot model doesn't have.
  val isArgumentProjected = argument.variance != Variance.INVARIANT
  if (isArgumentProjected || declaredVariance == null) {
    return BoundCheck.UNKNOWN
  }
  // A use-site projection in the bound overrides the declared variance.
  val variance =
    if (boundArgument.variance == Variance.INVARIANT) {
      declaredVariance
    } else {
      boundArgument.variance
    }
  return when (variance) {
    Variance.OUT_VARIANCE -> checkBound(argument.type, boundArgument.type)
    Variance.IN_VARIANCE -> checkBound(boundArgument.type, argument.type)
    Variance.INVARIANT -> {
      // Two different class types in an invariant position can't be subtypes of each other.
      val bothPlain =
        argument.type.identity.isPlainClassType() && boundArgument.type.identity.isPlainClassType()
      if (bothPlain) {
        BoundCheck.FAILS
      } else {
        BoundCheck.UNKNOWN
      }
    }
  }
}

/** Whether this type is built only from class types, so identity equality is type equality. */
private fun TypeIdentity.isPlainClassType(): Boolean {
  if (this !is TypeIdentity.Class) {
    return false
  }
  return arguments.all { argument ->
    when (argument) {
      TypeArgumentIdentity.Star -> true
      is TypeArgumentIdentity.Projection -> argument.type.isPlainClassType()
    }
  }
}

private const val ANY_CLASS_ID = "kotlin/Any"
private const val NOTHING_CLASS_ID = "kotlin/Nothing"

private fun TypeIdentity.nullability(): Nullability? {
  return when (this) {
    is TypeIdentity.Class -> nullability
    is TypeIdentity.TypeParameter -> nullability
    else -> null
  }
}

private fun TypeIdentity.withNullability(nullability: Nullability): TypeIdentity {
  return when (this) {
    is TypeIdentity.Class -> copy(nullability = nullability)
    is TypeIdentity.TypeParameter -> copy(nullability = nullability)
    else -> this
  }
}

/**
 * Follows [path] through type arguments. [matchTypePattern] built [path] from this snapshot's
 * identity. Snapshot arguments and identity arguments come from the same alias-expanded type, so
 * their positions line up.
 */
internal fun KaTypeSnapshot.typeArgumentAt(path: List<Int>): KaTypeSnapshot? {
  var current = this
  for (index in path) {
    val argument = current.typeArguments.getOrNull(index) as? KaTypeArgumentSnapshot.Typed
    if (argument == null) {
      return null
    }
    current = argument.type
  }
  return current
}

/** Replaces the substituted type parameters in every type of this contextual key. */
internal fun KaContextualTypeKey.substitute(
  substitutions: Map<TypeIdentity.TypeParameter, KaTypeSnapshot>,
): KaContextualTypeKey? {
  val keyType = typeKey.type.substitute(substitutions) ?: return null
  var failed = false
  val wrapped: WrappedType<KaTypeSnapshot> = wrappedType.mapTypes { type ->
    val substituted = type.substitute(substitutions)
    if (substituted == null) {
      failed = true
      type
    } else {
      substituted
    }
  }
  if (failed) {
    return null
  }
  val raw = rawType
  val substitutedRaw =
    if (raw == null) {
      null
    } else {
      raw.substitute(substitutions) ?: return null
    }
  return KaContextualTypeKey(
    typeKey = KaTypeKey(keyType, typeKey.qualifier),
    wrappedType = wrapped,
    hasDefault = hasDefault,
    rawType = substitutedRaw,
  )
}

/**
 * Replaces the type parameters in [substitutions] with their arguments. Returns this snapshot when
 * nothing changes. Returns null when a substituted type parameter sits inside a flexible,
 * intersection, or unresolved type, which the snapshot model can't rebuild.
 */
internal fun KaTypeSnapshot.substitute(
  substitutions: Map<TypeIdentity.TypeParameter, KaTypeSnapshot>,
): KaTypeSnapshot? {
  return when (val type = identity) {
    is TypeIdentity.TypeParameter -> {
      val replacement = substitutions[type.copy(nullability = Nullability.NotNull)] ?: return this
      when (type.nullability) {
        Nullability.NotNull -> replacement
        Nullability.Nullable -> replacement.withNullability(nullable = true)
        Nullability.DefinitelyNotNull -> replacement.withNullability(nullable = false)
      }
    }
    is TypeIdentity.Class -> substituteClass(type, substitutions)
    else -> {
      if (type.mentionsAny(substitutions.keys)) {
        null
      } else {
        this
      }
    }
  }
}

private fun KaTypeSnapshot.substituteClass(
  type: TypeIdentity.Class,
  substitutions: Map<TypeIdentity.TypeParameter, KaTypeSnapshot>,
): KaTypeSnapshot? {
  if (typeArguments.size != type.arguments.size) {
    if (type.mentionsAny(substitutions.keys)) {
      return null
    }
    return this
  }
  var changed = false
  val arguments = ArrayList<KaTypeArgumentSnapshot>(typeArguments.size)
  for (argument in typeArguments) {
    when (argument) {
      KaTypeArgumentSnapshot.Star -> arguments += argument
      is KaTypeArgumentSnapshot.Typed -> {
        val substituted = argument.type.substitute(substitutions) ?: return null
        if (substituted !== argument.type) {
          changed = true
        }
        arguments += KaTypeArgumentSnapshot.Typed(substituted, argument.variance)
      }
    }
  }
  if (!changed) {
    return this
  }
  val identityArguments =
    type.arguments.mapIndexed { index, original ->
      when (original) {
        TypeArgumentIdentity.Star -> original
        is TypeArgumentIdentity.Projection -> {
          val substituted = arguments[index].type ?: return null
          TypeArgumentIdentity.Projection(original.variance, substituted.identity)
        }
      }
    }
  val classId = classId ?: return null
  val isNullable = type.nullability == Nullability.Nullable
  return KaTypeSnapshot(
    renderedType = renderClassType(classId.asFqNameString(), arguments, isNullable, short = false),
    shortType = renderClassType(shortHead(classId), arguments, isNullable, short = true),
    classId = classId,
    identity = type.copy(arguments = identityArguments),
    typeArguments = arguments,
    isMarkedNullable = isMarkedNullable,
    isError = isError,
    unresolvedClassName = unresolvedClassName,
    hierarchy = hierarchy?.withoutSupertypes(),
  )
}

/**
 * The hierarchy of the same class with other type arguments. The class's declared variances carry
 * over. Its supertypes had the old arguments substituted, so they're dropped.
 */
private fun KaTypeHierarchy.withoutSupertypes(): KaTypeHierarchy {
  return KaTypeHierarchy(supertypes = null, parameterVariances = parameterVariances)
}

/** The short render's class name, like `Encoder` in `Encoder<List<T>>`. */
private fun KaTypeSnapshot.shortHead(classId: ClassId): String {
  val argumentsStart = shortType.indexOf('<')
  val startsWithClassName = !shortType.startsWith("(")
  if (argumentsStart > 0 && startsWithClassName) {
    return shortType.substring(0, argumentsStart)
  }
  return classId.shortClassName.asString()
}

private fun renderClassType(
  head: String,
  arguments: List<KaTypeArgumentSnapshot>,
  isNullable: Boolean,
  short: Boolean,
): String = buildString {
  append(head)
  if (arguments.isNotEmpty()) {
    append('<')
    arguments.forEachIndexed { index, argument ->
      if (index > 0) {
        append(", ")
      }
      when (argument) {
        KaTypeArgumentSnapshot.Star -> append('*')
        is KaTypeArgumentSnapshot.Typed -> {
          when (argument.variance) {
            Variance.INVARIANT -> {}
            Variance.IN_VARIANCE -> append("in ")
            Variance.OUT_VARIANCE -> append("out ")
          }
          val rendered =
            if (short) {
              argument.type.shortType
            } else {
              argument.type.renderedType
            }
          append(rendered)
        }
      }
    }
    append('>')
  }
  if (isNullable) {
    append('?')
  }
}

/**
 * This snapshot with its top-level nullability changed. Returns null for types whose nullability
 * the snapshot model can't change, like flexible types.
 */
internal fun KaTypeSnapshot.withNullability(nullable: Boolean): KaTypeSnapshot? {
  val target =
    if (nullable) {
      Nullability.Nullable
    } else {
      Nullability.NotNull
    }
  val newIdentity =
    when (val type = identity) {
      is TypeIdentity.Class -> {
        if (type.nullability == target) {
          return this
        }
        type.copy(nullability = target)
      }
      is TypeIdentity.TypeParameter -> {
        val alreadyNullable = type.nullability == Nullability.Nullable
        if (alreadyNullable == nullable) {
          return this
        }
        type.copy(nullability = target)
      }
      else -> return null
    }
  return KaTypeSnapshot(
    renderedType = renderedType.withNullabilitySuffix(nullable),
    shortType = shortType.withNullabilitySuffix(nullable),
    classId = classId,
    identity = newIdentity,
    typeArguments = typeArguments,
    isMarkedNullable = nullable,
    isError = isError,
    unresolvedClassName = unresolvedClassName,
    // Nullability doesn't change a type's supertypes or its class's variances.
    hierarchy = hierarchy,
  )
}

private fun String.withNullabilitySuffix(nullable: Boolean): String {
  if (nullable) {
    if (endsWith("?")) {
      return this
    }
    // Function types need parentheses before the nullability marker.
    if (isFunctionTypeRender()) {
      return "($this)?"
    }
    return "$this?"
  }
  if (!endsWith("?")) {
    return this
  }
  val withoutMarker = dropLast(1)
  val isWrapped = withoutMarker.startsWith("((") && withoutMarker.endsWith(")")
  if (isWrapped && withoutMarker.contains("->")) {
    return withoutMarker.substring(1, withoutMarker.length - 1)
  }
  return withoutMarker
}

private fun String.isFunctionTypeRender(): Boolean {
  val startsLikeFunction = startsWith("(") || startsWith("suspend (")
  return startsLikeFunction && contains("->")
}

/** Whether any type parameter in [variables] occurs anywhere in this identity. */
private fun TypeIdentity.mentionsAny(variables: Set<TypeIdentity.TypeParameter>): Boolean {
  return when (this) {
    is TypeIdentity.TypeParameter -> copy(nullability = Nullability.NotNull) in variables
    is TypeIdentity.Class ->
      arguments.any { argument ->
        argument is TypeArgumentIdentity.Projection && argument.type.mentionsAny(variables)
      }
    is TypeIdentity.Flexible -> lower.mentionsAny(variables) || upper.mentionsAny(variables)
    is TypeIdentity.Intersection -> types.any { it.mentionsAny(variables) }
    is TypeIdentity.Unresolved -> false
  }
}
