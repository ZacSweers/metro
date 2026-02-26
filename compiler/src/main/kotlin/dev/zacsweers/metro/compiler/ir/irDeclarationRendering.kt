// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.appendLineWithUnderlinedContent
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isPropertyField
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.Name

internal data class DiagnosticMetadata(val fullPath: String, val metadata: List<String>)

internal fun IrDeclaration.humanReadableDiagnosticMetadata(): DiagnosticMetadata {
  val fullPath =
    when (val declaration = this) {
      is IrDeclarationParent -> {
        kotlinFqName.asString()
      }
      is IrDeclarationWithName -> {
        fqNameWhenAvailable?.asString() ?: reportCompilerBug("No fqName for property")
      }
      else -> {
        reportCompilerBug("Unsupported declaration type: ${declaration.dumpKotlinLike()}")
      }
    }
  val metadata = mutableListOf<String>()

  fun addCallableMetadata(declaration: IrDeclaration, originalContainer: IrClass) {
    val callableMetadata = declaration.getAnnotation(Symbols.FqNames.CallableMetadataClass)!!
    val propertyName = callableMetadata.constArgumentOfTypeAt<String>(1)!!
    val callableName = propertyName.ifBlank { callableMetadata.constArgumentOfTypeAt<String>(0)!! }
    val functionSuffix = if (propertyName.isBlank()) "(...)" else ""
    metadata +=
      "This is Metro-generated code for '${originalContainer.kotlinFqName}.${callableName}$functionSuffix' (where the problem is)."
  }

  // Add more metadata to the error if it's generated metro code
  parentClassOrNull?.let { parentClass ->
    val parent = parentClass.parent

    if (parent !is IrClass) {
      metadata +=
        "Parent declaration of '${parent.kotlinFqName}' is not readable. " +
          "This may be a sign that it is used but not in the compile classpath." +
          "\n\nKotlin-like dump:\n${dumpKotlinLike()}"
      return@let
    }

    if (hasAnnotation(Symbols.ClassIds.CallableMetadata)) {
      // It's a Binds callable. ParentClass is a BindsMirror
      // Get the original binding container, which may be a generated metro contribution
      val originalContainer = parent
      if (originalContainer.hasAnnotation(Symbols.ClassIds.metroContribution)) {
        // If it's a `@MetroContribution`, get the original contributing class
        val origin = originalContainer.parentAsClass
        metadata +=
          "This is Metro-generated code that contributes '${origin.kotlinFqName}' (where the problem is) to ${originalContainer.getAnnotation(Symbols.ClassIds.metroContribution.asSingleFqName())!!.scopeOrNull()!!.shortClassName}."
      } else {
        // If it's not a `@MetroContribution`, just mention it's binding code from the original
        // binding container
        addCallableMetadata(this, originalContainer)
      }
    } else if (parentClass.hasAnnotation(Symbols.ClassIds.metroContribution)) {
      // If this is just a generic contribution (i.e. `@ContributesTo`)
      val origin = parent
      metadata +=
        "This is Metro-generated code that contributes '${origin.kotlinFqName}' (where the problem is) to ${parentClass.getAnnotation(Symbols.ClassIds.metroContribution.asSingleFqName())!!.scopeOrNull()!!.shortClassName}."
    } else if (parentClass.hasAnnotation(Symbols.ClassIds.CallableMetadata)) {
      // It's a provider factory. ParentClass is a provider mirror
      addCallableMetadata(parentClass, parent)
    }
  }

  return DiagnosticMetadata(fullPath, metadata)
}

context(builder: StringBuilder)
internal fun IrClass.renderForDiagnostic(
  short: Boolean,
  annotations: MetroAnnotations<IrAnnotation>,
  underlineTypeKey: Boolean,
) {
  with(builder) {
    renderAnnotations(annotations, short, isClass = false)
    append(kind.codeRepresentation)
    append(' ')
    if (underlineTypeKey) {
      appendLineWithUnderlinedContent(name.asString())
    } else {
      append(name.asString())
    }
  }
}

internal enum class Format {
  DECLARATION,
  CALL;

  val isDeclaration: Boolean
    get() = this == DECLARATION

  val isCall: Boolean
    get() = this == CALL
}

internal fun StringBuilder.renderForDiagnostic(
  declaration: IrDeclarationParent,
  short: Boolean,
  typeKey: IrTypeKey,
  annotations: MetroAnnotations<IrAnnotation>?,
  parameters: Parameters,
  isProperty: Boolean?,
  underlineTypeKey: Boolean,
  format: Format = Format.DECLARATION,
) {
  return renderForDiagnosticImpl(
    declaration = declaration,
    short = short,
    typeKey = typeKey,
    annotations = annotations,
    parameters = parameters,
    isProperty = isProperty,
    underlineTypeKey = underlineTypeKey,
    format = format,
  )
}

private fun StringBuilder.renderForDiagnosticImpl(
  declaration: IrDeclarationParent,
  short: Boolean,
  typeKey: IrTypeKey? = null,
  annotations: MetroAnnotations<IrAnnotation>? = null,
  parameters: Parameters = Parameters.empty(),
  isProperty: Boolean? = null,
  underlineTypeKey: Boolean = false,
  format: Format = Format.DECLARATION,
) {
  val property: IrProperty?
  val name: Name
  val type: IrType
  when (declaration) {
    is IrField -> {
      property =
        if (declaration.isPropertyField) {
          declaration.correspondingPropertySymbol?.owner
        } else {
          null
        }
      name = declaration.name
      type = declaration.type
    }
    is IrFunction -> {
      property = declaration.propertyIfAccessor.expectAsOrNull<IrProperty>()
      name = (property ?: declaration).name
      type = declaration.returnType
    }
    else -> {
      reportCompilerBug("Unsupported declaration type: ${declaration.dumpKotlinLike()}")
    }
  }

  val isProperty = isProperty == true || property != null

  if (format.isDeclaration) {
    annotations?.let { renderAnnotations(it, short, isClass = false) }
    if (isProperty) {
      if (property != null) {
        if (property.isVar) {
          if (property.isLateinit) {
            append("lateinit ")
          }
          append("var ")
        } else {
          append("val ")
        }
      } else {
        append("val ")
      }
    } else {
      append("fun ")
    }

    if (parameters.contextParameters.isNotEmpty()) {
      parameters.contextParameters.joinTo(this, ", ", prefix = "context(", postfix = ")\n") {
        it.name.asString() + ": " + it.typeKey.render(short = short)
      }
    }
  }

  val dispatchReceiverName =
    declaration.parentClassOrNull?.sourceGraphIfMetroGraph?.name?.asString()
  var hasReceiver = false

  if (format.isCall) {
    dispatchReceiverName?.let {
      append(it)
      hasReceiver = true
    }
  }

  parameters.extensionReceiverParameter?.let {
    if (format.isCall) {
      // Put the receiver in parens for context
      append('(')
    }
    it.typeKey.qualifier?.let { qualifier ->
      append(qualifier.render(short = short, "receiver"))
      append(' ')
    }
    append(it.typeKey.render(short = short))
    if (format.isCall) {
      // Put the receiver in parens for context
      append(')')
    }
    hasReceiver = true
  }

  if (hasReceiver) {
    append('.')
  }

  append(name.asString())

  val paramsToDisplay =
    if (format.isCall) {
      // Likely member inject() call
      parameters.regularParameters.filterNot { it.isAssisted }
    } else {
      parameters.regularParameters
    }
  if (paramsToDisplay.isNotEmpty()) {
    paramsToDisplay.joinTo(this, ", ", prefix = "(", postfix = ")\n") {
      it.name.asString() + ": " + it.typeKey.render(short = short, includeQualifier = true)
    }
  } else if (!isProperty) {
    append("()")
  }

  if (typeKey != null && !(declaration is IrFunction && type.isUnit())) {
    append(": ")
    val returnTypeString = typeKey.render(short = short, includeQualifier = false)
    if (underlineTypeKey) {
      appendLineWithUnderlinedContent(returnTypeString)
    } else {
      append(returnTypeString)
    }
  }
}

/**
 * Renders an [IrOverridableDeclaration] (function or property) for diagnostics. This is a
 * convenience overload that works directly with IR declarations without requiring binding-level
 * types like [IrTypeKey] or [Parameters], rendering the return type from the IR type directly.
 */
internal fun StringBuilder.renderForDiagnostic(
  declaration: IrOverridableDeclaration<*>,
  short: Boolean = false,
  annotations: MetroAnnotations<IrAnnotation>? = null,
) {
  val function =
    when (declaration) {
      is IrSimpleFunction -> declaration
      is IrProperty -> declaration.getter ?: return
    }
  val isProperty = declaration is IrProperty || function.correspondingPropertySymbol != null
  // Render annotations, val/fun keyword, and name using existing infra
  renderForDiagnosticImpl(
    declaration = function,
    short = short,
    annotations = annotations,
    isProperty = isProperty,
  )
  // Render return type from IR type since we don't have an IrTypeKey
  val returnType = function.returnType
  if (!returnType.isUnit()) {
    append(": ")
    append(returnType.render(short = short))
  }
}

/**
 * Creates a [LocationDiagnostic] for an [IrOverridableDeclaration]. Uses [renderSourceLocation] for
 * the location (respecting [MetroOptions.SystemProperties.SHORTEN_LOCATIONS]) and
 * [renderForDiagnostic] for the description.
 */
internal fun IrOverridableDeclaration<*>.renderLocationDiagnostic(
  shortLocation: Boolean = MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
  annotations: MetroAnnotations<IrAnnotation>? = null,
  short: Boolean = false,
): LocationDiagnostic {
  val location =
    (this as IrDeclaration).renderSourceLocation(short = shortLocation)
      ?: parentAsClass.kotlinFqName.asString()
  val description = buildString {
    renderForDiagnostic(
      declaration = this@renderLocationDiagnostic,
      short = short,
      annotations = annotations,
    )
  }
  return LocationDiagnostic(location, description)
}

private fun StringBuilder.renderAnnotations(
  annotations: MetroAnnotations<IrAnnotation>,
  short: Boolean,
  isClass: Boolean,
) {
  val annotationStrings =
    with(annotations) {
      buildList {
        qualifier?.let { add(it.render(short = short)) }
        if (isBinds) add("@Binds")
        if (isProvides) add("@Provides")
        if (isIntoSet) add("@IntoSet")
        if (isElementsIntoSet) add("@ElementsIntoSet")
        if (isMultibinds) add("@Multibinds")
        if (isBindsOptionalOf) add("@BindsOptionalOf")
        scope?.let { add(it.render(short = short)) }
        if (isIntoMap) add("@IntoMap")
        mapKey?.let { add(it.render(short = short)) }
        if (isClass) {
          if (isInject) add("@Inject")
        }
      }
    }
  when (annotationStrings.size) {
    0 -> {
      // do nothing
    }
    1,
    2 -> {
      annotationStrings.joinTo(this, " ")
      append(' ')
    }
    else -> {
      annotationStrings.joinTo(this, "\n")
      appendLine()
    }
  }
}
