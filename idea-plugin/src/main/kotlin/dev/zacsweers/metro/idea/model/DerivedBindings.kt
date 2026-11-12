// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.graph.DerivedBindingDiagnosticMessages
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
 * Bounds are checked without an analysis session, since the index outlives its sessions. A bound of
 * `Any` or `Any?`, a bound equal to the argument, and a nullable argument for a non-null class
 * bound are decided exactly. Other class bounds need a subtype check the snapshot model can't do.
 * Those are assumed to hold and the derivation is marked with [BindingDerivation.boundsVerified]
 * set to false. Assuming they hold avoids reporting missing bindings for valid code.
 */
internal fun matchGenericBinding(template: KaBinding, key: KaTypeKey): KaBinding? {
  val signature = template.genericSignature ?: return null
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
      arguments += argument.withNullability(nullable = false) ?: return null
    } else {
      arguments += argument
    }
  }

  val substitutions = HashMap<TypeIdentity.TypeParameter, KaTypeSnapshot>()
  for ((index, parameter) in signature.typeParameters.withIndex()) {
    substitutions[parameter.identity] = arguments[index]
  }

  var boundsVerified = true
  for ((index, parameter) in signature.typeParameters.withIndex()) {
    for (bound in parameter.upperBounds) {
      val substitutedBound = bound.substitute(substitutions)
      if (substitutedBound == null) {
        boundsVerified = false
        continue
      }
      when (checkBound(arguments[index], substitutedBound)) {
        BoundCheck.HOLDS -> {}
        BoundCheck.FAILS -> return null
        BoundCheck.UNKNOWN -> boundsVerified = false
      }
    }
  }

  val derivation = BindingDerivation(template, arguments, boundsVerified)
  return template.instantiate(key, substitutions, derivation)
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
 * the other with only its own type parameters as variables. So `Encoder<List<T>>` is
 * alpha-equivalent to `Encoder<List<E>>`.
 */
internal fun KaBinding.isAlphaEquivalentTo(other: KaBinding): Boolean {
  val signature = genericSignature ?: return false
  val otherSignature = other.genericSignature ?: return false
  if (typeKey.qualifier?.identity != other.typeKey.qualifier?.identity) {
    return false
  }
  val pattern = typeKey.type.identity
  val otherPattern = other.typeKey.type.identity
  val otherCoversThis = matchTypePattern(otherPattern, pattern, otherSignature::isVariable) != null
  if (!otherCoversThis) {
    return false
  }
  return matchTypePattern(pattern, otherPattern, signature::isVariable) != null
}

private enum class BoundCheck {
  HOLDS,
  FAILS,
  UNKNOWN,
}

/** Decides `argument <: bound` where the snapshot model can, and returns UNKNOWN elsewhere. */
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
  return BoundCheck.UNKNOWN
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
  )
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
