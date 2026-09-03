// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.reporting

import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingDeclaration
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.compiler.graph.explanation.BindingSourceLocation
import dev.zacsweers.metro.compiler.graph.explanation.bindingExplanationId
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.render
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Uses qualified signatures for stable IDs; source offsets are kept only as navigation metadata.
 */
internal fun IrDeclarationWithName.bindingExplanationDeclaration(): BindingDeclaration {
  val location = locationOrNull()
  val source =
    if (location == null || location.line <= 0) {
      null
    } else {
      val path = location.path.substringAfterLast('/').substringAfterLast('\\')
      BindingSourceLocation(path, location.line, location.column.takeIf { it > 0 })
    }
  return BindingDeclaration(
    id = bindingDeclarationId(),
    label = declarationFqName(),
    source = source,
  )
}

private fun IrDeclarationWithName.declarationFqName(): String =
  when (this) {
    is IrDeclarationParent -> kotlinFqName.asString()
    else -> parent.kotlinFqName.child(name).asString()
  }

private fun IrDeclarationWithName.bindingDeclarationId(): String =
  when (this) {
    is IrValueParameter -> {
      val owner = parent as? IrFunction
      val ownerId = owner?.bindingDeclarationId() ?: parent.kotlinFqName.asString()
      "$ownerId#${name.asString()}"
    }
    is IrFunction ->
      buildString {
        append(kotlinFqName.asString())
        append('(')
        append(parameters.joinToString(",") { it.type.render(short = false) })
        append("): ")
        append(returnType.render(short = false))
      }
    is IrProperty -> "${declarationFqName()}: ${getter?.returnType?.render(short = false)}"
    else -> declarationFqName()
  }

/** Describes an existing selected binding without creating any alternative bindings. */
internal fun IrBinding.bindingExplanationCandidate(
  ownerGraphIds: Map<IrTypeKey, String>,
  selectedOwner: IrTypeKey? = null,
  conflict: Boolean = false,
): BindingExplanationCandidate {
  val owner =
    selectedOwner
      ?: when (this) {
        is IrBinding.GraphDependency -> token?.ownerGraphKey
        is IrBinding.BoundInstance -> token?.ownerGraphKey
        else -> null
      }
  val selectedReason =
    when {
      conflict -> BindingReason.CONFLICT
      owner != null -> BindingReason.SELECTED_PARENT
      else -> selectionReason()
    }
  val declaration =
    when (this) {
      is IrBinding.ConstructorInjected -> explicitBinding?.function ?: reportableDeclaration
      else -> reportableDeclaration
    }?.bindingExplanationDeclaration()
  val key = contextualTypeKey.render(short = false, includeQualifier = true)
  return BindingExplanationCandidate(
    id = bindingExplanationId(key, declaration?.id ?: "generated"),
    key = key,
    status = if (conflict) BindingCandidateStatus.CONFLICT else BindingCandidateStatus.SELECTED,
    reason = selectedReason,
    declaration = declaration,
    ownerGraphId = owner?.let { ownerGraphIds[it] ?: it.render(short = false) },
  )
}

private fun IrBinding.selectionReason(): BindingReason =
  when (this) {
    is IrBinding.Provided -> BindingReason.SELECTED_EXPLICIT
    is IrBinding.Alias ->
      if (bindsCallable == null) BindingReason.SELECTED_GENERATED
      else BindingReason.SELECTED_EXPLICIT
    is IrBinding.BoundInstance ->
      if (isGraphInput) BindingReason.SELECTED_EXPLICIT else BindingReason.SELECTED_GENERATED
    is IrBinding.GraphDependency -> BindingReason.SELECTED_EXPLICIT
    is IrBinding.Multibinding -> BindingReason.SELECTED_MULTIBINDING
    is IrBinding.CustomWrapper -> BindingReason.SELECTED_OPTIONAL
    is IrBinding.ConstructorInjected ->
      if (explicitBinding == null) BindingReason.SELECTED_IMPLICIT
      else BindingReason.SELECTED_EXPLICIT
    is IrBinding.ObjectClass,
    is IrBinding.AssistedFactory -> BindingReason.SELECTED_IMPLICIT
    is IrBinding.GraphExtension,
    is IrBinding.GraphExtensionFactory,
    is IrBinding.MembersInjected,
    is IrBinding.Absent -> BindingReason.SELECTED_GENERATED
  }
