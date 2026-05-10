/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
//
// Fork of org.jetbrains.kotlin.ir.util.dumpKotlinLike (from Kotlin compiler).
//
// UPSTREAM SOURCE:
//   compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/util/dumpKotlinLike.kt
//
// CHANGES FROM UPSTREAM (all marked with "METRO CHANGE" comments):
//   1. Renamed KotlinLikeDumper → BetterKotlinLikeDumper.
//   2. Added classNameTransformer parameter for customizable class name rendering.
//   3. Tracks currentContainer field for passing context to classNameTransformer.
//   4. IrSymbol.safeName, safeParentClassName, and callable reference rendering all
//      delegate to classNameTransformer instead of using simple names.
//   5. Removed VariableNameData/normalizedName (internal APIs) — replaced with simple name access.
//   6. Local copy of stableOrdered() (internal API).
//
// NOTE: visitClass is intentionally NOT changed — class declarations keep simple names.
// Only references to classes in expressions go through classNameTransformer.
//
// The default classNameTransformer (nestedClassName) renders fully qualified nested class names
// (e.g. "ExampleGraph.Impl" instead of just "Impl"), which is much more readable in diagnostic
// reports for dependency injection graphs.
//
// To update: diff this file against the upstream source and re-apply the marked changes.
package dev.zacsweers.metro.compiler.ir

// METRO CHANGE: removed com.intellij.openapi.util.text.StringUtil dependency,
// replaced with local escapeString/escapeChar helpers below.
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrOverridableMember
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.declarations.path
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCallableReference
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrDynamicMemberExpression
import org.jetbrains.kotlin.ir.expressions.IrDynamicOperator
import org.jetbrains.kotlin.ir.expressions.IrDynamicOperatorExpression
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorCallExpression
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetClass
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInlinedFunctionBlock
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrRawFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin.Companion.OBJECT_LITERAL
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBody
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.BodyPrintingStrategy
import org.jetbrains.kotlin.ir.util.CustomKotlinLikeDumpStrategy.Modifiers
import org.jetbrains.kotlin.ir.util.FakeOverridesStrategy
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.VisibilityPrintingStrategy
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isFakeOverriddenFromAny
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.Printer

/**
 * Like [org.jetbrains.kotlin.ir.util.dumpKotlinLike] but supports a [classNameTransformer] hook for
 * customizing how class names are rendered in expressions. Requires a [CompatContext] so the
 * version-specific [KotlinLikeDumpOptions] default constructor lives in a compat module compiled
 * against the matching Kotlin runtime.
 *
 * The default transformer renders nested class names with their full enclosing class chain (e.g.
 * `ExampleGraph.Impl` instead of just `Impl`).
 *
 * @param classNameTransformer receives the current container declaration (scope) and the class
 *   whose name is being rendered. Returns the string to print.
 */
context(compat: CompatContext)
public fun IrElement.betterDumpKotlinLike(
  options: KotlinLikeDumpOptions = compat.defaultKotlinLikeDumpOptions(),
  classNameTransformer: (context: IrDeclaration?, declaration: IrDeclarationWithName) -> String =
    ::nestedClassNameRenderer,
): String {
  val sb = StringBuilder()
  BetterKotlinLikeDumper(
      Printer(sb, 1, "  "),
      options,
      compat.printVariableInitializersCompat(options),
      classNameTransformer,
    )
    .printElement(this)
  return sb.toString()
}

// Local copy of internal stableOrdered() from DumpIrTree.kt
private fun List<IrDeclaration>.stableOrdered(): List<IrDeclaration> {
  val strictOrder = hashMapOf<IrDeclaration, Int>()
  var idx = 0
  forEach {
    val shouldPreserveRelativeOrder =
      when (it) {
        is IrProperty -> it.backingField != null && !it.isConst
        is IrAnonymousInitializer,
        is IrEnumEntry,
        is IrField -> true
        else -> false
      }
    if (shouldPreserveRelativeOrder) {
      strictOrder[it] = idx++
    }
  }
  return sortedWith { a, b ->
    val strictA = strictOrder[a] ?: Int.MAX_VALUE
    val strictB = strictOrder[b] ?: Int.MAX_VALUE
    if (strictA == strictB) {
      a.render().compareTo(b.render())
    } else strictA - strictB
  }
}

private class BetterKotlinLikeDumper(
  val p: Printer,
  val options: KotlinLikeDumpOptions,
  // METRO CHANGE: routed through CompatContext.printVariableInitializersCompat because the
  // underlying KotlinLikeDumpOptions field doesn't exist in Kotlin < 2.3.0.
  val printVariableInitializers: Boolean,
  // METRO CHANGE: customizable class name rendering
  val classNameTransformer: (context: IrDeclaration?, declaration: IrDeclarationWithName) -> String,
) : IrVisitor<Unit, IrDeclaration?>() {
  private var currentWhenStmt: IrWhen? = null
  // METRO CHANGE: track current container for classNameTransformer context
  private var currentContainer: IrDeclaration? = null

  // METRO CHANGE: use classNameTransformer for class name rendering
  private val IrSymbol.safeName
    get() =
      if (!isBound) {
        "/* ERROR: unbound symbol $signature */"
      } else {
        when (val owner = owner) {
          is IrVariable -> owner /* hello */.name.asString()
          is IrDeclarationWithName -> classNameTransformer(currentContainer, owner)
          else -> "/* ERROR: unnamed symbol $signature */"
        }
      }

  private val IrFunctionSymbol.safeParameters
    get() =
      if (!isBound) {
        null
      } else {
        owner.parameters
      }

  // METRO CHANGE: use classNameTransformer for class name rendering
  private val IrSymbol.safeParentClassName
    get() =
      if (!isBound) {
        "/* ERROR: unbound symbol $signature */"
      } else {
        (owner as? IrDeclaration)?.parentClassOrNull?.let {
          classNameTransformer(currentContainer, it)
        } ?: "/* ERROR: unexpected parent for $safeName */"
      }

  private val IrSymbol.safeParentClassOrNull
    get() =
      if (!isBound) {
        null
      } else {
        (owner as? IrDeclaration)?.parentClassOrNull
      }

  fun printElement(element: IrElement) {
    element.accept(this, null)
  }

  fun printType(type: IrType) {
    type.printTypeWithNoIndent()
  }

  fun printTypeArgument(typeArg: IrTypeArgument) {
    typeArg.printTypeArgumentWithNoIndent()
  }

  @JvmName("orderedDeclarations") // Prevent JVM signature clash
  private fun List<IrDeclaration>.ordered() = if (options.stableOrder) stableOrdered() else this

  @JvmName("orderedTypes") // Prevent JVM signature clash
  private fun List<IrType>.ordered(): List<IrType> {
    if (!options.stableOrder) return this

    fun isNonInterfaceType(type: IrType) =
      type.classifierOrNull?.let { it !is IrClassSymbol || !it.owner.isInterface } ?: true

    val (classTypes, interfaceTypes) = partition(::isNonInterfaceType)

    return classTypes.sortedBy(IrType::render) + interfaceTypes.sortedBy(IrType::render)
  }

  private inline fun wrap(element: IrElement, container: IrDeclaration?, block: () -> Unit) {
    if (!options.customDumpStrategy.willPrintElement(element, container, p, options)) return
    // METRO CHANGE: track current container for transformClassName context
    val previousContainer = currentContainer
    if (element is IrDeclaration) {
      currentContainer = element
    }
    try {
      block()
    } finally {
      currentContainer = previousContainer
      options.customDumpStrategy.didPrintElement(element, container, p)
    }
  }

  override fun visitElement(element: IrElement, data: IrDeclaration?) {
    val e = "/* ERROR: unsupported element type: " + element.javaClass.simpleName + " */"
    if (element is IrExpression) {
      // TODO move text to the message?
      // TODO better process expressions and statements
      p.printlnWithNoIndent("error(\"\") $e")
    } else {
      p.println(e)
    }
  }

  override fun visitModuleFragment(declaration: IrModuleFragment, data: IrDeclaration?) =
    wrap(declaration, data) {
      p.println("// MODULE: ${declaration.name.asString()}")
      declaration.acceptChildren(this, null)
    }

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  override fun visitFile(declaration: IrFile, data: IrDeclaration?) =
    wrap(declaration, data) {
      if (options.printRegionsPerFile) p.println("//region block: ${declaration.name}")

      if (options.printFileName) p.println("// FILE: ${declaration.name}")
      if (options.printFilePath) p.println("// path: ${declaration.path}")
      declaration.printlnAnnotations("file")
      val packageFqName = declaration.packageFqName
      if (!packageFqName.isRoot) {
        p.println("package ${packageFqName.asString()}")
      }
      if (!p.isEmpty) p.printlnWithNoIndent()

      if (options.printMemberDeclarations) {
        declaration.declarations.ordered().forEach { it.accept(this, null) }
      }

      if (options.printRegionsPerFile) p.println("//endregion")
    }

  override fun visitClass(declaration: IrClass, data: IrDeclaration?): Unit =
    wrap(declaration, data) {
      // TODO omit super class for enums, annotations?
      // TODO omit Companion name for companion objects?
      // TODO do we need to print info about `thisReceiver`?
      // TODO special support for objects?
      if (declaration.isExpect && !options.printExpectDeclarations) return

      declaration.printlnAnnotations()
      p.printIndent()

      declaration.run {
        printModifiersWithNoIndent(
          this,
          Modifiers(
            visibility = visibility,
            isExpect = isExpect,
            modality = modality,
            isExternal = isExternal,
            isInner = isInner,
            isValue = isValue,
            isData = isData,
            isCompanion = isCompanion,
            isFunInterface = isFun,
            classKind = kind,
          ),
        )
      }

      p.printWithNoIndent(declaration.name.asString())

      declaration.printTypeParametersWithNoIndent()
      // TODO no test
      if (declaration.superTypes.isNotEmpty()) {
        var first = true
        for (type in declaration.superTypes.ordered()) {
          if (type.isAny()) continue

          if (!first) {
            p.printWithNoIndent(", ")
          } else {
            p.printWithNoIndent(" : ")
            first = false
          }

          type.printTypeWithNoIndent()
        }
      }

      declaration.printWhereClauseIfNeededWithNoIndent()

      p.printlnWithNoIndent(" {")

      if (options.printMemberDeclarations) {
        p.pushIndent()
        declaration.declarations.ordered().forEach { it.accept(this, declaration) }
        p.popIndent()
      }

      p.println("}")
      p.printlnWithNoIndent()
    }

  /*
  https://kotlinlang.org/docs/reference/coding-conventions.html#modifiers
  Modifiers order:
  public / protected / private / internal
  expect / actual
  final / open / abstract / sealed / const
  external
  override
  lateinit
  tailrec
  vararg
  suspend
  inner
  enum / annotation / fun // as a modifier in `fun interface`
  companion
  inline
  value
  infix
  operator
  data
   */
  private fun printModifiersWithNoIndent(declaration: IrDeclaration, modifiers: Modifiers) =
    options.customDumpStrategy.transformModifiersForDeclaration(declaration, modifiers).run {
      val isInterfaceMember =
        declaration is IrOverridableMember && (declaration.parent as? IrClass)?.isInterface == true
      printVisibility(visibility)
      p(isExpect, "expect") // TODO actual?
      val defaultModality =
        when {
          isInterfaceMember || (isOverride || isFakeOverride) && modality == Modality.OPEN ->
            Modality.OPEN
          classKind == ClassKind.INTERFACE -> Modality.ABSTRACT
          else -> Modality.FINAL
        }
      p(modality, defaultModality) { name.lowercase() }
      p(isExternal, "external")
      p(isFakeOverride, customModifier("fake"))
      p(isOverride, "override")
      p(isLateinit, "lateinit")
      p(isTailrec, "tailrec")
      printParameterModifiersWithNoIndent(
        isVararg,
        isCrossinline,
        isNoinline,
        isHidden,
        isAssignable,
      )
      p(isSuspend, "suspend")
      p(isInner, "inner")
      p(isInline, "inline")
      p(isValue, "value")
      p(isData, "data")
      p(isCompanion, "companion")
      p(isFunInterface, "fun")
      p(classKind) {
        name.lowercase().replace('_', ' ') + if (this == ClassKind.ENUM_ENTRY) " class" else ""
      }
      p(isInfix, "infix")
      p(isOperator, "operator")
    }

  private fun printVisibility(visibility: DescriptorVisibility) {
    // TODO don't print visibility if it's not changed in override?
    val shouldBePrinted =
      visibility != DescriptorVisibilities.DEFAULT_VISIBILITY ||
        options.visibilityPrintingStrategy == VisibilityPrintingStrategy.ALWAYS

    p(condition = shouldBePrinted, visibility.name)
  }

  private fun printParameterModifiersWithNoIndent(
    isVararg: Boolean,
    isCrossinline: Boolean,
    isNoinline: Boolean,
    isHidden: Boolean,
    isAssignable: Boolean,
  ) {
    p(isVararg, "vararg")
    p(isCrossinline, "crossinline")
    p(isNoinline, "noinline")
    p(isHidden, customModifier("hidden"))
    p(isAssignable, customModifier("var"))
  }

  private fun IrTypeParametersContainer.printTypeParametersWithNoIndent(postfix: String = "") {
    if (typeParameters.isEmpty()) return

    p.printWithNoIndent("<")
    typeParameters.forEachIndexed { i, typeParam ->
      p(i > 0, ",")

      typeParam.printATypeParameterWithNoIndent()
    }
    p.printWithNoIndent(">")
    p.printWithNoIndent(postfix)
  }

  private fun IrTypeParameter.printATypeParameterWithNoIndent() {
    variance.printVarianceWithNoIndent()
    p(isReified, "reified")

    printAnnotationsWithNoIndent()

    p.printWithNoIndent(name.asString())

    if (superTypes.size == 1) {
      p.printWithNoIndent(" : ")
      superTypes.single().printTypeWithNoIndent()
    }
  }

  private fun Variance.printVarianceWithNoIndent() {
    p(this, Variance.INVARIANT) { label }
  }

  private fun filterAnnotations(
    annotations: List<IrConstructorCall>,
    container: IrAnnotationContainer,
  ): List<IrConstructorCall> = annotations.filter {
    options.customDumpStrategy.shouldPrintAnnotation(it, container)
  }

  private fun IrAnnotationContainer.printAnnotationsWithNoIndent() {
    filterAnnotations(annotations, this).forEach {
      it.printAnAnnotationWithNoIndent()
      p.printWithNoIndent(" ")
    }
  }

  private fun IrAnnotationContainer.printlnAnnotations(prefix: String = "") {
    filterAnnotations(annotations, this).forEach {
      p.printIndent()
      it.printAnAnnotationWithNoIndent(prefix)
      p.printlnWithNoIndent()
    }
  }

  private fun IrConstructorCall.printAnAnnotationWithNoIndent(prefix: String = "") {
    p.printWithNoIndent("@" + (if (prefix.isEmpty()) "" else "$prefix:"))
    visitConstructorCall(this, null)
  }

  private fun IrTypeParametersContainer.printWhereClauseIfNeededWithNoIndent() {
    if (typeParameters.none { it.superTypes.size > 1 }) return

    p.printWithNoIndent(" where ")

    var first = true
    typeParameters.forEach {
      if (it.superTypes.size > 1) {
        // TODO no test with more than one generic parameter with more supertypes
        first = it.printWhereClauseTypesWithNoIndent(first)
      }
    }
  }

  private fun IrTypeParameter.printWhereClauseTypesWithNoIndent(first: Boolean): Boolean {
    var myFirst = first
    superTypes.ordered().forEach { type ->
      if (!myFirst) {
        p.printWithNoIndent(", ")
      } else {
        myFirst = false
      }

      p.printWithNoIndent(name.asString())
      p.printWithNoIndent(" : ")
      type.printTypeWithNoIndent()
    }

    return myFirst
  }

  private fun IrType.printTypeWithNoIndent() {
    // TODO don't print `Any?` as upper bound?
    printAnnotationsWithNoIndent()
    when (this) {
      is IrSimpleType -> {
        // TODO abbreviation

        val dnn =
          classifier is IrTypeParameterSymbol &&
            nullability == SimpleTypeNullability.DEFINITELY_NOT_NULL
        if (dnn) {
          p.printWithNoIndent("(")
        }

        p.printWithNoIndent(classifier.safeName)

        if (arguments.isNotEmpty()) {
          p.printWithNoIndent("<")
          arguments.forEachIndexed { i, typeArg ->
            p(i > 0, ",")

            typeArg.printTypeArgumentWithNoIndent()
          }
          p.printWithNoIndent(">")
        }

        if (dnn) {
          p.printWithNoIndent(" & Any)")
        }

        if (isMarkedNullable()) p.printWithNoIndent("?")
      }
      is IrDynamicType -> p.printWithNoIndent("dynamic")
      is IrErrorType -> p.printWithNoIndent("ErrorType")
    }
  }

  private fun IrTypeArgument.printTypeArgumentWithNoIndent() {
    when (this) {
      is IrStarProjection -> p.printWithNoIndent("*")
      is IrTypeProjection -> {
        variance.printVarianceWithNoIndent()
        type.printTypeWithNoIndent()
      }
    }
  }

  override fun visitTypeAlias(declaration: IrTypeAlias, data: IrDeclaration?) =
    wrap(declaration, data) {
      declaration.printlnAnnotations()
      p.printIndent()

      printVisibility(declaration.visibility)
      p(declaration.isActual, "actual")

      p.printWithNoIndent("typealias ")
      p.printWithNoIndent(declaration.name.asString())
      declaration.printTypeParametersWithNoIndent()
      p.printWithNoIndent(" = ")
      declaration.expandedType.printTypeWithNoIndent()

      p.printlnWithNoIndent()
    }

  override fun visitEnumEntry(declaration: IrEnumEntry, data: IrDeclaration?) =
    wrap(declaration, data) {
      // TODO better rendering for enum entries

      declaration.correspondingClass?.let { p.println() }

      declaration.printlnAnnotations()
      p.printIndent()
      p.printWithNoIndent(declaration.name)
      declaration.initializerExpression?.let {
        if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
          // it's not valid kotlin
          p.printWithNoIndent(" = ")
        }
        it.accept(this, declaration)
      }
      p.println()

      declaration.correspondingClass?.accept(this, declaration)

      p.println()
    }

  override fun visitAnonymousInitializer(
    declaration: IrAnonymousInitializer,
    data: IrDeclaration?,
  ) =
    wrap(declaration, data) {
      // TODO no tests, looks like IrAnonymousInitializer has annotations accidentally.
      declaration.printlnAnnotations()
      p.printIndent()

      // TODO no tests, looks like there are no irText tests for isStatic flag
      p(declaration.isStatic, customModifier("static"))
      p.printWithNoIndent("init")
      if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
        p.printWithNoIndent(" ")
      }
      declaration.body.accept(this, declaration)

      p.printlnWithNoIndent()
    }

  override fun visitSimpleFunction(declaration: IrSimpleFunction, data: IrDeclaration?) {
    if (declaration.isExpect && !options.printExpectDeclarations) return
    val keyword = buildString {
      if (declaration.isStatic) {
        append(customModifier("static"))
        append(' ')
      }
      append("fun ")
    }
    declaration.printSimpleFunction(
      data,
      keyword,
      declaration.name.asString(),
      printTypeParametersAndExtensionReceiver = true,
      printSignatureAndBody = true,
      printExtraTrailingNewLine = true,
    )
  }

  override fun visitConstructor(declaration: IrConstructor, data: IrDeclaration?) =
    wrap(declaration, data) {
      // TODO name?
      // TODO is it worth to merge code for IrConstructor and IrSimpleFunction?
      // TODO dispatchReceiverParameter -- outer `this` for inner classes
      // TODO return type?

      declaration.printlnAnnotations()
      declaration.printContextParameters()
      p.printIndent()

      declaration.run {
        printModifiersWithNoIndent(
          this,
          Modifiers(
            visibility = visibility,
            isExpect = isExpect,
            isExternal = isExternal,
            isInline = isInline,
          ),
        )
      }

      p.printWithNoIndent("constructor")
      declaration.printTypeParametersWithNoIndent()
      declaration.printRegularParametersWithNoIndent()
      declaration.printWhereClauseIfNeededWithNoIndent()
      if (declaration.isPrimary) {
        p.printWithNoIndent(" ", customModifier("primary"))
      }
      declaration.body?.let {
        if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
          p.printWithNoIndent(" ")
        }
        it.accept(this, declaration)
      }
      p.printlnWithNoIndent()
      p.printlnWithNoIndent()
    }

  private fun IrSimpleFunction.printSimpleFunction(
    data: IrDeclaration?,
    keyword: String,
    name: String,
    printTypeParametersAndExtensionReceiver: Boolean,
    printSignatureAndBody: Boolean,
    printExtraTrailingNewLine: Boolean,
    finishWithNewLine: Boolean = true,
  ) {
    /* TODO
       correspondingProperty
       overridden
       dispatchReceiverParameter
    */

    if (
      options.printFakeOverridesStrategy == FakeOverridesStrategy.NONE && isFakeOverride ||
        options.printFakeOverridesStrategy == FakeOverridesStrategy.ALL_EXCEPT_ANY &&
          isFakeOverriddenFromAny()
    ) {
      return
    }

    wrap(this, data) {
      printlnAnnotations()

      if (printSignatureAndBody) {
        printContextParameters()
      }

      p.print("")

      printModifiersWithNoIndent(
        this,
        Modifiers(
          visibility = visibility,
          isExpect = isExpect,
          modality = modality,
          isExternal = isExternal,
          isOverride = overriddenSymbols.isNotEmpty(),
          isFakeOverride = isFakeOverride,
          isTailrec = isTailrec,
          isSuspend = isSuspend,
          isInline = isInline,
          isInfix = isInfix,
          isOperator = isOperator,
        ),
      )

      p.printWithNoIndent(keyword)

      if (printTypeParametersAndExtensionReceiver) printTypeParametersWithNoIndent(postfix = " ")

      if (printTypeParametersAndExtensionReceiver) {
        parameters
          .firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
          ?.printExtensionReceiverParameter()
      }

      p.printWithNoIndent(name)

      if (printSignatureAndBody) {
        printRegularParametersWithNoIndent()

        if (options.printUnitReturnType || !returnType.isUnit()) {
          p.printWithNoIndent(": ")
          returnType.printTypeWithNoIndent()
        }
        printWhereClauseIfNeededWithNoIndent()

        body?.let {
          if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
            p.printWithNoIndent(" ")
          }
          it.accept(this@BetterKotlinLikeDumper, null)
        }
      }

      if (
        !printSignatureAndBody ||
          body == null ||
          options.bodyPrintingStrategy != BodyPrintingStrategy.PRINT_BODIES
      ) {
        if (finishWithNewLine) {
          p.printlnWithNoIndent()
        }
      }

      if (printExtraTrailingNewLine) p.printlnWithNoIndent()
    }
  }

  private fun IrValueParameter.printExtensionReceiverParameter() {
    type.printTypeWithNoIndent()
    p.printWithNoIndent(".")
  }

  private fun IrFunction.printRegularParametersWithNoIndent() {
    p.printWithNoIndent("(")
    parameters
      .filter { it.kind == IrParameterKind.Regular }
      .forEachIndexed { i, param ->
        p(i > 0, ",")
        param.printAValueParameterWithNoIndent(this)
      }
    p.printWithNoIndent(")")
  }

  private fun IrFunction.printContextParameters() {
    val contextParameters = parameters.filter { it.kind == IrParameterKind.Context }
    if (contextParameters.isNotEmpty()) {
      p.print("context(")
      contextParameters.forEachIndexed { i, param ->
        p(i > 0, ",")
        param.printAValueParameterWithNoIndent(this)
      }
      p.printlnWithNoIndent(")")
    }
  }

  private fun IrValueParameter.printAValueParameterWithNoIndent(data: IrDeclaration?) {
    printAnnotationsWithNoIndent()

    printParameterModifiersWithNoIndent(
      isVararg = varargElementType != null,
      isCrossinline,
      isNoinline,
      // TODO no test
      isHidden,
      // TODO no test
      isAssignable,
    )

    p.printWithNoIndent(name.asString())
    p.printWithNoIndent(": ")
    (varargElementType ?: type).printTypeWithNoIndent()
    // TODO print it.type too for varargs?

    defaultValue?.let { v ->
      if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
        p.printWithNoIndent(" = ")
      }
      v.accept(this@BetterKotlinLikeDumper, data)
    }
  }

  override fun visitTypeParameter(declaration: IrTypeParameter, data: IrDeclaration?) =
    wrap(declaration, data) {
      declaration.printATypeParameterWithNoIndent()
      if (declaration.superTypes.size > 1) declaration.printWhereClauseTypesWithNoIndent(true)
    }

  override fun visitValueParameter(declaration: IrValueParameter, data: IrDeclaration?) =
    wrap(declaration, data) {
      // TODO index?
      declaration.printAValueParameterWithNoIndent(data)
    }

  override fun visitProperty(declaration: IrProperty, data: IrDeclaration?): Unit =
    wrap(declaration, data) {
      if (
        options.printFakeOverridesStrategy == FakeOverridesStrategy.NONE &&
          declaration.isFakeOverride
      )
        return

      declaration.printlnAnnotations()
      p.printIndent()

      // TODO better rendering for modifiers on property and accessors
      //  modifiers that could be different between accessors and property have a comment
      declaration.run {
        printModifiersWithNoIndent(
          this,
          Modifiers(
            // accessors by default have same visibility, but the can define own value
            visibility = visibility,
            isExpect = isExpect,
            modality = modality,
            isExternal = isExternal,
            // couldn't be different for getter, possible for set, but it's invalid kotlin
            isOverride = getter?.overriddenSymbols?.isNotEmpty() == true,
            isFakeOverride = isFakeOverride,
            isLateinit = isLateinit,
            isSuspend = getter?.isSuspend == true,
            // could be used on property if all accessors have same state, otherwise must be defined
            // on each accessor
            isInline = false,
          ),
        )
      }

      // TODO don't print borrowed flags and assert that they are same with a setter or don't
      // borrow?
      // TODO we can omit type for set parameter

      p(declaration.isConst, "const")
      p(declaration.getter?.isStatic == true, customModifier("static"))
      p.printWithNoIndent(if (declaration.isVar) "var" else "val")
      p.printWithNoIndent(" ")

      // TODO assert that typeparameters and receiver are ~same for getter ans setter

      declaration.getter?.printTypeParametersWithNoIndent(postfix = " ")

      declaration.getter
        ?.parameters
        ?.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        ?.printExtensionReceiverParameter()

      p.printWithNoIndent(declaration.name.asString())

      val backingField = declaration.backingField

      if (backingField != null && !declaration.isDelegated) {
        p.printWithNoIndent(": ")
        backingField.type.printTypeWithNoIndent()
      } else {
        declaration.getter?.returnType?.let {
          p.printWithNoIndent(": ")
          it.printTypeWithNoIndent()
        }
      }

      /* TODO better rendering for
       * field
       * isDelegated
       * delegated w/o backing field
       * provideDelegate
       */

      if (declaration.isDelegated) {
        p.printWithNoIndent(" ", commentBlock("by"))
      }

      p.printlnWithNoIndent()

      if (options.printMemberDeclarations) {
        p.pushIndent()

        // TODO share code with visitField?
        // it's not valid kotlin
        declaration.backingField?.initializer?.let {
          if (options.bodyPrintingStrategy != BodyPrintingStrategy.NO_BODIES) {
            // If the strategy is PRINT_ONLY_LOCAL_CLASSES_AND_FUNCTIONS, the local declarations in
            // the backing field initializer
            // will be printed under 'field'.
            p.print("field")
          }
          if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
            p.printWithNoIndent(" = ")
          }
          it.accept(this, declaration)
          if (options.bodyPrintingStrategy != BodyPrintingStrategy.NO_BODIES) {
            p.printlnWithNoIndent()
          }
        }

        // TODO generate better name for set parameter `<set-?>`?
        declaration.getter?.printAccessor("get", declaration)
        declaration.setter?.printAccessor("set", declaration)

        p.popIndent()
        p.printlnWithNoIndent()
      }
    }

  private fun IrSimpleFunction.printAccessor(s: String, property: IrDeclaration) {
    val isCustomAccessor = origin != Origins.FirstParty.DEFAULT_PROPERTY_ACCESSOR
    printSimpleFunction(
      property,
      keyword = "",
      name = s,
      printTypeParametersAndExtensionReceiver = false,
      printSignatureAndBody = isCustomAccessor,
      printExtraTrailingNewLine = false,
    )
  }

  override fun visitField(declaration: IrField, data: IrDeclaration?) =
    wrap(declaration, data) {
      declaration.printlnAnnotations()
      p.printIndent()

      declaration.run {
        printModifiersWithNoIndent(
          this,
          Modifiers(visibility = visibility, isExternal = isExternal),
        )
      }

      if (declaration.isStatic || declaration.isFinal) {
        // it's not valid kotlin unless it's commented
        p.printWithNoIndent(CUSTOM_MODIFIER_START)
        p(declaration.isStatic, "static")
        p(declaration.isFinal, "final")
        p.printWithNoIndent("field")
        p.printWithNoIndent(CUSTOM_MODIFIER_END)
        p.printWithNoIndent(" ")
      }

      p.printWithNoIndent(
        when {
          declaration.correspondingPropertySymbol != null -> "field "
          declaration.isFinal -> "val "
          else -> "var "
        }
      )
      p.printWithNoIndent(declaration.name.asString() + ": ")
      declaration.type.printTypeWithNoIndent()

      declaration.initializer?.let {
        if (options.bodyPrintingStrategy == BodyPrintingStrategy.PRINT_BODIES) {
          p.printWithNoIndent(" = ")
        }
        it.accept(this, declaration)
      }

      // TODO correspondingPropertySymbol

      p.printlnWithNoIndent()
    }

  override fun visitVariable(declaration: IrVariable, data: IrDeclaration?) =
    wrap(declaration, data) {
      declaration.printlnAnnotations()
      p.printIndent()

      p(declaration.isLateinit, "lateinit")
      p(declaration.isConst, "const")
      declaration.run { printVariable(isVar, name.asString(), type) }

      if (printVariableInitializers) {
        declaration.initializer?.let {
          p.printWithNoIndent(" = ")
          it.accept(this, declaration)
        }
      }
    }

  override fun visitLocalDelegatedProperty(
    declaration: IrLocalDelegatedProperty,
    data: IrDeclaration?,
  ) =
    wrap(declaration, data) {
      declaration.printlnAnnotations()
      p.printIndent()

      // TODO think about better rendering
      declaration.run { printVariable(isVar, name.asString(), type) }

      p.printlnWithNoIndent()
      p.pushIndent()

      declaration.delegate?.accept(this, declaration)
      p.printlnWithNoIndent()

      declaration.getter.printAccessor("get", declaration)
      declaration.setter?.printAccessor("set", declaration)

      p.popIndent()
      p.printlnWithNoIndent()
    }

  private fun printVariable(isVar: Boolean, name: String, type: IrType) {
    p.printWithNoIndent(if (isVar) "var" else "val")
    p.printWithNoIndent(" ")
    p.printWithNoIndent(name)
    p.printWithNoIndent(": ")
    type.printTypeWithNoIndent()
  }

  private fun <Body : IrBody> printBody(
    body: Body,
    data: IrDeclaration?,
    actuallyPrint: () -> Unit,
  ) =
    wrap(body, data) {
      when (options.bodyPrintingStrategy) {
        BodyPrintingStrategy.NO_BODIES -> {}
        BodyPrintingStrategy.PRINT_ONLY_LOCAL_CLASSES_AND_FUNCTIONS ->
          body.acceptChildren(
            // Don't print bodies, but print local classes and functions declared in those bodies
            object : IrVisitor<Unit, IrDeclaration?>() {
              override fun visitElement(element: IrElement, data: IrDeclaration?) {
                element.acceptChildren(this, data)
              }

              override fun visitDeclaration(declaration: IrDeclarationBase, data: IrDeclaration?) {
                p.println()
                p.pushIndent()
                declaration.accept(this@BetterKotlinLikeDumper, data)
                p.popIndent()
              }

              override fun visitVariable(declaration: IrVariable, data: IrDeclaration?) {
                declaration.acceptChildren(this, data)
              }

              override fun visitLocalDelegatedProperty(
                declaration: IrLocalDelegatedProperty,
                data: IrDeclaration?,
              ) {
                declaration.acceptChildren(this, data)
              }
            },
            data,
          )
        BodyPrintingStrategy.PRINT_BODIES -> actuallyPrint()
      }
    }

  override fun visitExpressionBody(body: IrExpressionBody, data: IrDeclaration?) {
    printBody(body, data) {
      // TODO should we print something here?
      body.expression.accept(this, data)
    }
  }

  override fun visitBlockBody(body: IrBlockBody, data: IrDeclaration?) {
    printBody(body, data) {
      body.printStatementContainer("{", "}", data)
      p.printlnWithNoIndent()
    }
  }

  override fun visitComposite(expression: IrComposite, data: IrDeclaration?) =
    wrap(expression, data) {
      expression.printStatementContainer("// COMPOSITE {", "// }", data, withIndentation = false)
    }

  override fun visitBlock(expression: IrBlock, data: IrDeclaration?): Unit =
    wrap(expression, data) {
      // TODO special blocks using `origin`
      // TODO inlinedFunctionSymbol for IrReturnableBlock
      // TODO no tests for IrReturnableBlock?
      if (expression.origin == OBJECT_LITERAL && options.collapseObjectLiteralBlock) {
        p.printWithNoIndent("<anonymous object>")
        return
      }
      val kind =
        when (expression) {
          is IrReturnableBlock -> "RETURNABLE BLOCK"
          is IrInlinedFunctionBlock -> "INLINED FUNCTION BLOCK"
          else -> "BLOCK"
        }
      // it's not valid kotlin
      expression.printStatementContainer("{ // $kind", "}", data)
    }

  private fun IrStatementContainer.printStatementContainer(
    before: String,
    after: String,
    data: IrDeclaration?,
    withIndentation: Boolean = true,
  ) {
    // TODO type for IrContainerExpression
    p.printlnWithNoIndent(before)
    if (withIndentation) p.pushIndent()

    statements.forEach {
      if (it is IrExpression) p.printIndent()
      it.accept(this@BetterKotlinLikeDumper, data)
      p.printlnWithNoIndent()
    }

    if (withIndentation) p.popIndent()
    p.print(after)
  }

  override fun visitSyntheticBody(body: IrSyntheticBody, data: IrDeclaration?) {
    printBody(body, data) {
      // it's not valid kotlin
      p.printlnWithNoIndent("/* Synthetic body for ${body.kind} */")
    }
  }

  override fun visitCall(expression: IrCall, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO process specially builtin symbols
      expression.printMemberAccessExpressionWithNoIndent(
        expression.symbol.safeName,
        expression.symbol.safeParameters,
        expression.superQualifierSymbol,
        omitAllBracketsIfNoArguments = false,
        data = data,
      )
    }

  override fun visitConstructorCall(expression: IrConstructorCall, data: IrDeclaration?) =
    wrap(expression, data) {
      expression.printMemberAccessExpressionWithNoIndent(
        expression.symbol.safeParentClassName,
        expression.symbol.safeParameters,
        superQualifierSymbol = null,
        omitAllBracketsIfNoArguments =
          expression.symbol.safeParentClassOrNull?.isAnnotationClass == true,
        data = data,
      )
    }

  private fun IrMemberAccessExpression<*>.printMemberAccessExpressionWithNoIndent(
    name: String,
    valueParameters: List<IrValueParameter>?,
    superQualifierSymbol: IrClassSymbol?,
    omitAllBracketsIfNoArguments: Boolean,
    data: IrDeclaration?,
    accessOperator: String = ".",
    omitAccessOperatorIfNoReceivers: Boolean = true,
    wrapArguments: Boolean = false,
  ) {
    // TODO origin

    val dispatchReceiverParameter =
      valueParameters?.getOrNull(0)?.takeIf { it.kind == IrParameterKind.DispatchReceiver }
    if (superQualifierSymbol != null) {
      // TODO which super? smart mode?
      p.printWithNoIndent("super<${superQualifierSymbol.safeName}>")
    } else if (dispatchReceiverParameter != null) {
      val dispatchReceiver = arguments.getOrNull(0)
      if (dispatchReceiver != null) {
        dispatchReceiver.accept(this@BetterKotlinLikeDumper, data)
      } else if (this is IrCallableReference<*>) {
        // TODO where from to get type arguments for a class?
        // TODO render it also for static members (from java)
        symbol.safeParentClassOrNull?.let {
          // METRO CHANGE: use classNameTransformer for class name rendering
          p.printWithNoIndent(classNameTransformer(currentContainer, it))
        }
      } else {
        p.printWithNoIndent("<missing-receiver>")
      }
    }

    if (
      !omitAccessOperatorIfNoReceivers ||
        (dispatchReceiverParameter != null || superQualifierSymbol != null)
    ) {
      p.printWithNoIndent(accessOperator)
    }

    p.printWithNoIndent(name)

    if (
      omitAllBracketsIfNoArguments &&
        typeArguments.isEmpty() &&
        (valueParameters.orEmpty() zip this.arguments)
          .filter { it.first.kind != IrParameterKind.DispatchReceiver }
          .all { it.second == null }
    ) {
      return
    }

    if (wrapArguments) p.printWithNoIndent("/*")

    if (typeArguments.isNotEmpty()) {
      p.printWithNoIndent("<")
      for ((i, param) in typeArguments.withIndex()) {
        p(i > 0, ",")
        // TODO flag to print type param name?
        param?.printTypeWithNoIndent() ?: p.printWithNoIndent(commentBlock("null"))
      }
      p.printWithNoIndent(">")
    }

    p.printWithNoIndent("(")
    var isCommentOpen = false
    var printComma = false
    for (paramIdx in 0..<maxOf(valueParameters?.size ?: 0, arguments.size)) {
      val param = valueParameters?.getOrNull(paramIdx)
      if (param?.kind == IrParameterKind.DispatchReceiver) {
        continue
      }

      // TODO should we print something for omitted arguments (== null)?
      val arg = arguments.getOrNull(paramIdx)
      if (arg != null) {
        if (printComma) p.printWithNoIndent(",")
        when (param?.kind) {
          IrParameterKind.DispatchReceiver -> {}
          IrParameterKind.Context,
          IrParameterKind.ExtensionReceiver -> {
            if (!wrapArguments && !isCommentOpen) {
              p.printWithNoIndent("/* ")
              isCommentOpen = true
            }
          }
          IrParameterKind.Regular,
          null -> {
            if (!wrapArguments && isCommentOpen) {
              p.printWithNoIndent(" */")
              isCommentOpen = false
            }
          }
        }
        if (printComma) p.printWithNoIndent(' ')

        when {
          param != null -> p.printWithNoIndent(param.name.asString() + " = ")
          valueParameters != null -> p.printWithNoIndent("\$EXCESSIVE\$ = ")
          else -> {}
        }

        if (paramIdx > arguments.lastIndex) {
          p.printWithNoIndent("<missing-argument>")
        } else {
          arg.accept(this@BetterKotlinLikeDumper, data)
        }

        printComma = true
      }
    }
    if (!wrapArguments && isCommentOpen) p.printWithNoIndent(" */")
    p.printWithNoIndent(")")

    if (wrapArguments) p.printWithNoIndent(" */")
  }

  override fun visitDelegatingConstructorCall(
    expression: IrDelegatingConstructorCall,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      // TODO skip call to Any?
      expression.printConstructorCallWithNoIndent(data)
    }

  override fun visitEnumConstructorCall(expression: IrEnumConstructorCall, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO skip call to Enum?
      expression.printConstructorCallWithNoIndent(data)
    }

  private fun IrFunctionAccessExpression.printConstructorCallWithNoIndent(data: IrDeclaration?) {
    // TODO flag to omit comment block?
    val delegatingClass = symbol.safeParentClassOrNull
    val currentClass = data?.parent as? IrClass
    val delegatingClassName = symbol.safeParentClassName

    val name =
      if (data is IrConstructor) {
        when (currentClass) {
          // it's not valid kotlin, it's fallback for the case when data wasn't provided
          null -> "delegating/*$delegatingClassName*/"
          delegatingClass -> "this/*$delegatingClassName*/"
          else -> "super/*$delegatingClassName*/"
        }
      } else {
        delegatingClassName // required only for IrEnumConstructorCall
      }

    printMemberAccessExpressionWithNoIndent(
      name,
      symbol.safeParameters,
      superQualifierSymbol = null,
      omitAllBracketsIfNoArguments = false,
      data = data,
    )
  }

  override fun visitInstanceInitializerCall(
    expression: IrInstanceInitializerCall,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      // TODO assert that `expression.classSymbol.owner == data.parentAsClass
      // TODO better rendering
      p.printlnWithNoIndent("/* <init>() */")
    }

  override fun visitFunctionExpression(expression: IrFunctionExpression, data: IrDeclaration?) {
    // TODO omit the name when it's possible
    // TODO Is there a difference between `<anonymous>` and `<no name provided>`?
    // TODO Is name of function used somewhere? How it's important?
    // TODO Use lambda syntax when possible
    // TODO don't print visibility?
    p.withholdIndentOnce()
    expression.function.printSimpleFunction(
      data,
      "fun ",
      expression.function.name.asString(),
      printTypeParametersAndExtensionReceiver = true,
      printSignatureAndBody = true,
      printExtraTrailingNewLine = false,
    )
  }

  private fun IrSimpleFunction.dumpForBoundReference(data: IrDeclaration?) =
    printSimpleFunction(
      data = data,
      keyword = "fun",
      name = "",
      printTypeParametersAndExtensionReceiver = true,
      printSignatureAndBody = true,
      printExtraTrailingNewLine = false,
      finishWithNewLine = false,
    )

  override fun visitRichFunctionReference(
    expression: IrRichFunctionReference,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      p.printWithNoIndent(expression.type.dumpKotlinLike())
      p.printlnWithNoIndent("(")
      p.pushIndent()
      p.print("/* bound = */ [")
      expression.boundValues.forEachIndexed { index, value ->
        if (index != 0) p.printWithNoIndent(",")
        value.accept(this, data)
      }
      p.printlnWithNoIndent("],")
      p.print("/* invoke = */")
      expression.invokeFunction.dumpForBoundReference(data)
      p.popIndent()
      p.println(")")
    }

  override fun visitRichPropertyReference(
    expression: IrRichPropertyReference,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      p.printWithNoIndent(expression.type.dumpKotlinLike())
      p.printlnWithNoIndent("(")
      p.pushIndent()
      p.print("/* bound = */ [")
      expression.boundValues.forEachIndexed { index, value ->
        if (index != 0) p.printWithNoIndent(",")
        value.accept(this, data)
      }
      p.printlnWithNoIndent("],")
      p.print("/* getter = */")
      expression.getterFunction.dumpForBoundReference(data)
      expression.setterFunction?.let {
        p.print("/* setter = */")
        it.dumpForBoundReference(data)
      }
      p.popIndent()
      p.println(")")
    }

  override fun visitGetField(expression: IrGetField, data: IrDeclaration?) =
    wrap(expression, data) { expression.printFieldAccess(data) }

  override fun visitSetField(expression: IrSetField, data: IrDeclaration?) =
    wrap(expression, data) {
      expression.printFieldAccess(data)
      p.printWithNoIndent(" = ")
      expression.value.accept(this, data)
    }

  private fun IrFieldAccessExpression.printFieldAccess(data: IrDeclaration?) {
    // it's not valid kotlin
    receiver?.accept(this@BetterKotlinLikeDumper, data)
    superQualifierSymbol?.let {
      // TODO which supper? smart mode?
      // it's not valid kotlin
      if (receiver != null) p.printWithNoIndent("(")
      p.printWithNoIndent("super<${it.safeName}>")
      if (receiver != null) p.printWithNoIndent(")")
    }

    if (receiver != null || superQualifierSymbol != null) {
      p.printWithNoIndent(".")
    }

    // it's not valid kotlin
    p.printWithNoIndent("#" + symbol.safeName)
  }

  override fun visitGetValue(expression: IrGetValue, data: IrDeclaration?) =
    wrap(expression, data) { p.printWithNoIndent(expression.symbol.safeName) }

  override fun visitSetValue(expression: IrSetValue, data: IrDeclaration?) =
    wrap(expression, data) {
      p.printWithNoIndent(expression.symbol.safeName + " = ")
      expression.value.accept(this, data)
    }

  override fun visitGetObjectValue(expression: IrGetObjectValue, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO what if symbol is unbound?
      expression.symbol.defaultType.printTypeWithNoIndent()
    }

  override fun visitGetEnumValue(expression: IrGetEnumValue, data: IrDeclaration?) =
    wrap(expression, data) {
      p.printWithNoIndent(expression.symbol.safeParentClassName)
      p.printWithNoIndent(".")
      p.printWithNoIndent(expression.symbol.safeName)
    }

  override fun visitRawFunctionReference(expression: IrRawFunctionReference, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO support
      // TODO no test
      // it's not valid kotlin
      p.printWithNoIndent("&")
      super.visitRawFunctionReference(expression, data)
    }

  override fun visitReturn(expression: IrReturn, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO label
      // TODO optionally don't print Unit when return type of returnTargetSymbol is Unit
      p.printWithNoIndent("return ")
      expression.value.accept(this, data)
    }

  override fun visitThrow(expression: IrThrow, data: IrDeclaration?) {
    p.printWithNoIndent("throw ")
    expression.value.accept(this, data)
  }

  override fun visitStringConcatenation(expression: IrStringConcatenation, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO use triple quotes when possible?
      // TODO optionally each argument at a separate line, another option add a wrapping
      expression.arguments.forEachIndexed { i, e ->
        p(i > 0, " +")
        e.accept(this, data)
      }
    }

  override fun visitConst(expression: IrConst, data: IrDeclaration?) =
    wrap(expression, data) {
      val kind = expression.kind

      val (prefix, postfix) =
        when (kind) {
          is IrConstKind.Null -> "" to ""
          is IrConstKind.Boolean -> "" to ""
          is IrConstKind.Char -> "'" to "'"
          // it's not valid kotlin
          is IrConstKind.Byte -> "" to "B"
          // it's not valid kotlin
          is IrConstKind.Short -> "" to "S"
          is IrConstKind.Int -> "" to ""
          is IrConstKind.Long -> "" to "L"
          is IrConstKind.String -> "\"" to "\""
          is IrConstKind.Float -> "" to "F"
          is IrConstKind.Double -> "" to ""
        }

      val value = expression.value.toString()
      val safeValue =
        when (kind) {
          // TODO no tests for escaping quotes (',")
          is IrConstKind.Char -> escapeString(value)
          is IrConstKind.String -> escapeString(value)
          else -> value
        }

      p.printWithNoIndent(prefix, safeValue, postfix)
    }

  override fun visitVararg(expression: IrVararg, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO better rendering?
      // TODO varargElementType
      // it's not valid kotlin
      p.printWithNoIndent("[")
      expression.elements.forEachIndexed { i, e ->
        p(i > 0, ",")
        e.accept(this, data)
      }
      p.printWithNoIndent("]")
    }

  override fun visitSpreadElement(spread: IrSpreadElement, data: IrDeclaration?) =
    wrap(spread, data) {
      p.printWithNoIndent("*")
      spread.expression.accept(this, data)
    }

  override fun visitTypeOperator(expression: IrTypeOperatorCall, data: IrDeclaration?) =
    wrap(expression, data) {
      val (operator, after) =
        when (expression.operator) {
          IrTypeOperator.CAST -> "as" to ""
          IrTypeOperator.IMPLICIT_CAST -> "/*as" to " */"
          IrTypeOperator.IMPLICIT_NOTNULL -> "/*!!" to " */"
          IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> "/*~>" to " */"
          IrTypeOperator.SAFE_CAST -> "as?" to ""
          IrTypeOperator.INSTANCEOF -> "is" to ""
          IrTypeOperator.NOT_INSTANCEOF -> "!is" to ""
          IrTypeOperator.IMPLICIT_INTEGER_COERCION -> "/*~>" to " */"
          IrTypeOperator.SAM_CONVERSION -> "/*->" to " */"
          IrTypeOperator.IMPLICIT_DYNAMIC_CAST -> "/*~>" to " */"
          IrTypeOperator.REINTERPRET_CAST -> "/*=>" to " */"
        }

      expression.argument.accept(this, data)
      p.printWithNoIndent(" $operator ")
      expression.typeOperand.printTypeWithNoIndent()
      p.printWithNoIndent(after)
    }

  override fun visitWhen(expression: IrWhen, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO print if when possible?
      p.printlnWithNoIndent("when {")
      p.pushIndent()

      val savedWhenStmt = currentWhenStmt
      currentWhenStmt = expression
      expression.branches.forEach { it.accept(this, data) }
      currentWhenStmt = savedWhenStmt

      p.popIndent()
      p.print("}")
    }

  override fun visitBranch(branch: IrBranch, data: IrDeclaration?) =
    wrap(branch, data) {
      p.printIndent()
      branch.condition.let {
        // Deserialized IR contains no IrElseBranch nodes. They are represented with
        // IrBranch(condition=true)
        // To match Kotlin-like IR dump before serialization, the following logic tried to infer IR
        // node which was before serialization
        if (
          options.inferElseBranches &&
            it is IrConst &&
            it.value == true &&
            branch == currentWhenStmt?.branches?.last()
        )
          p.printWithNoIndent("else")
        else it.accept(this, data)
      }
      p.printWithNoIndent(" -> ")
      branch.result.accept(this, data)
      p.println()
    }

  override fun visitElseBranch(branch: IrElseBranch, data: IrDeclaration?) =
    wrap(branch, data) {
      p.printIndent()
      if ((branch.condition as? IrConst)?.value == true) {
        p.printWithNoIndent("else")
      } else {
        p.printWithNoIndent("/* else */ ")
        branch.condition.accept(this, data)
      }
      p.printWithNoIndent(" -> ")
      branch.result.accept(this, data)
      p.println()
    }

  private fun IrLoop.printLabel() {
    label?.let {
      p.printWithNoIndent(it)
      p.printWithNoIndent("@ ")
    }
  }

  override fun visitWhileLoop(loop: IrWhileLoop, data: IrDeclaration?) =
    wrap(loop, data) {
      loop.printLabel()

      p.printWithNoIndent("while (")
      loop.condition.accept(this, data)
      p.printWithNoIndent(") ")

      loop.body?.accept(this, data)
    }

  override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: IrDeclaration?) =
    wrap(loop, data) {
      loop.printLabel()

      p.printWithNoIndent("do")

      loop.body?.accept(this, data)

      p.print("while (")
      loop.condition.accept(this, data)
      p.printWithNoIndent(")")
    }

  override fun visitBreakContinue(jump: IrBreakContinue, data: IrDeclaration?) =
    wrap(jump, data) {
      // TODO render loop reference
      p.printWithNoIndent(if (jump is IrContinue) "continue" else "break")
      jump.label?.let {
        p.printWithNoIndent("@")
        p.printWithNoIndent(it)
      }
    }

  override fun visitTry(aTry: IrTry, data: IrDeclaration?) =
    wrap(aTry, data) {
      p.printWithNoIndent("try ")
      aTry.tryResult.accept(this, data)
      p.printlnWithNoIndent()

      aTry.catches.forEach { it.accept(this, data) }

      aTry.finallyExpression?.let {
        p.print("finally ")
        it.accept(this, data)
      }
    }

  override fun visitCatch(aCatch: IrCatch, data: IrDeclaration?) =
    wrap(aCatch, data) {
      p.print("catch (")
      aCatch.catchParameter.run {
        p.printWithNoIndent(name.asString())
        p.printWithNoIndent(": ")
        type.printTypeWithNoIndent()
      }
      p.printWithNoIndent(")")
      aCatch.result.accept(this, data)
      p.printlnWithNoIndent()
    }

  override fun visitGetClass(expression: IrGetClass, data: IrDeclaration?) =
    wrap(expression, data) {
      expression.argument.accept(this, data)
      p.printWithNoIndent("::class")
    }

  override fun visitClassReference(expression: IrClassReference, data: IrDeclaration?) =
    wrap(expression, data) {
      expression.classType.printTypeWithNoIndent()
      p.printWithNoIndent("::class")
    }

  override fun visitFunctionReference(expression: IrFunctionReference, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO reflectionTarget
      expression.printCallableReferenceWithNoIndent(expression.symbol.safeParameters, data)
    }

  override fun visitPropertyReference(expression: IrPropertyReference, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO do we need additional fields (field, getter, setter)?
      expression.printCallableReferenceWithNoIndent(expression.getter?.safeParameters, data)
    }

  override fun visitLocalDelegatedPropertyReference(
    expression: IrLocalDelegatedPropertyReference,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      // TODO do we need additional fields (delegate, getter, setter)?
      expression.printCallableReferenceWithNoIndent(expression.getter.safeParameters, data)
    }

  private fun IrCallableReference<*>.printCallableReferenceWithNoIndent(
    valueParameters: List<IrValueParameter>?,
    data: IrDeclaration?,
  ) {
    val target = symbol.owner as IrDeclarationWithName
    val name = if (target is IrConstructor) target.constructedClass.name else target.name
    printMemberAccessExpressionWithNoIndent(
      name.asString(),
      valueParameters,
      superQualifierSymbol = null,
      omitAllBracketsIfNoArguments = true,
      data = data,
      accessOperator = "::",
      omitAccessOperatorIfNoReceivers = false,
      wrapArguments = true,
    )
  }

  override fun visitDynamicOperatorExpression(
    expression: IrDynamicOperatorExpression,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      // TODO marker to show that it's dynamic call
      val s =
        when (val op = expression.operator) {
          IrDynamicOperator.ARRAY_ACCESS -> "[" to "]"
          IrDynamicOperator.INVOKE -> "(" to ")"

          // assert that arguments size is 0
          IrDynamicOperator.UNARY_PLUS,
          IrDynamicOperator.UNARY_MINUS,
          IrDynamicOperator.EXCL,
          IrDynamicOperator.PREFIX_INCREMENT,
          IrDynamicOperator.PREFIX_DECREMENT -> {
            p.printWithNoIndent(op.image)
            "" to ""
          }

          // assert that arguments size is 0
          IrDynamicOperator.POSTFIX_INCREMENT,
          IrDynamicOperator.POSTFIX_DECREMENT -> {
            op.image to ""
          }

          // assert that arguments size is 1
          else -> " ${op.image} " to ""
        }

      expression.receiver.accept(this, data)
      p.printWithNoIndent(s.first)
      expression.arguments.forEachIndexed { i, e ->
        p(i > 0, ",")
        e.accept(this, data)
      }
      p.printWithNoIndent(s.second)
    }

  override fun visitDynamicMemberExpression(
    expression: IrDynamicMemberExpression,
    data: IrDeclaration?,
  ) =
    wrap(expression, data) {
      expression.receiver.accept(this, data)
      p.printWithNoIndent(".")
      p.printWithNoIndent(expression.memberName)
    }

  override fun visitErrorExpression(expression: IrErrorExpression, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO description
      p.printWithNoIndent("error(\"\") /* ErrorExpression */")
    }

  override fun visitErrorCallExpression(expression: IrErrorCallExpression, data: IrDeclaration?) =
    wrap(expression, data) {
      // TODO description
      // TODO better rendering
      p.printWithNoIndent("error(\"\") /* ErrorCallExpression */")
      expression.explicitReceiver?.let {
        it.accept(this, data)
        p.printWithNoIndent("; ")
      }
      expression.arguments.forEach { arg ->
        arg.accept(this, data)
        p.printWithNoIndent("; ")
      }
    }

  private fun p(condition: Boolean, s: String) {
    if (condition) p.printWithNoIndent("$s ")
  }

  private fun <T : Any> p(value: T?, defaultValue: T? = null, getString: T.() -> String) {
    if (value == null) return
    p(value != defaultValue, value.getString())
  }

  private fun commentBlock(text: String) = "/* $text */"

  // it's not valid kotlin unless it's commented
  //  ^^^ it's applied to all usages of this function
  private fun customModifier(text: String): String {
    return CUSTOM_MODIFIER_START + text + CUSTOM_MODIFIER_END
  }

  private companion object {
    private const val CUSTOM_MODIFIER_START = "/* "
    private const val CUSTOM_MODIFIER_END = " */"
  }
}

// METRO CHANGE: local replacements for StringUtil.escapeStringCharacters / escapeCharCharacters
// to avoid a dependency on com.intellij.openapi.util.text.StringUtil.
private fun escapeString(s: String): String =
  buildString(s.length) {
    for (c in s) {
      when (c) {
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\\' -> append("\\\\")
        '\"' -> append("\\\"")
        '\$' -> append("\\\$")
        else ->
          if (c < ' ') {
            append("\\u${c.code.toString(16).padStart(4, '0')}")
          } else {
            append(c)
          }
      }
    }
  }
