// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.renderer.render

/** Detached annotation text and named arguments that the caller can turn into template fields. */
internal data class ContributionMapKeyChoice(
  val classId: ClassId,
  val label: String,
  val annotationText: String,
  val editableArguments: List<String>,
)

private val STRING_KEY = ClassId.fromString("dev/zacsweers/metro/StringKey")
private val CLASS_KEY = ClassId.fromString("dev/zacsweers/metro/ClassKey")
private val INT_KEY = ClassId.fromString("dev/zacsweers/metro/IntKey")
private val BUILT_IN_KEYS = listOf(CLASS_KEY, STRING_KEY, INT_KEY)

/**
 * Runs on an explicit contribution action. Kotlin annotation indexes supply source and binary
 * candidates; every candidate is resolved and checked in the target class's use-site session.
 * Required nested annotations and arrays are omitted because they need a larger editing flow.
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.contributionMapKeyChoices(
  owner: KtClassOrObject,
  options: MetroOptions,
): List<ContributionMapKeyChoice> {
  val ownerSymbol = owner.symbol as? KaNamedClassSymbol ?: return emptyList()
  existingMapKeyChoice(ownerSymbol, options)?.let {
    return listOf(it)
  }

  val visibility =
    createUseSiteVisibilityChecker(
      useSiteFile = owner.containingKtFile.symbol,
      receiverExpression = null,
      position = owner,
    )
  val candidateIds =
    linkedSetOf<ClassId>().apply {
      addAll(BUILT_IN_KEYS)
      addAll(mapKeyCandidateIds(owner, options.mapKeyAnnotations))
    }
  val choices = mutableListOf<Pair<ClassId, ContributionMapKeyChoice>>()
  for (classId in candidateIds) {
    ProgressManager.checkCanceled()
    val symbol = findClass(classId) as? KaNamedClassSymbol ?: continue
    if (!visibility.isVisible(symbol)) continue
    val choice = mapKeyChoice(symbol, ownerSymbol, options) ?: continue
    choices += classId to choice
  }
  val sortedChoices =
    choices.sortedWith(
      compareBy(
        { (classId, _) ->
          val builtInIndex = BUILT_IN_KEYS.indexOf(classId)
          if (builtInIndex >= 0) builtInIndex else Int.MAX_VALUE
        },
        { (classId, _) -> classId.asFqNameString() },
      )
    )
  return sortedChoices.map { it.second }
}

/** Revalidates one selected annotation without repeating project-wide key discovery. */
internal fun KaSession.contributionMapKeyChoice(
  owner: KtClassOrObject,
  options: MetroOptions,
  classId: ClassId,
): ContributionMapKeyChoice? {
  val ownerSymbol = owner.symbol as? KaNamedClassSymbol ?: return null
  val existing = existingMapKeyChoice(ownerSymbol, options)
  if (existing != null) return existing.takeIf { it.classId == classId }
  val annotationClass = findClass(classId) as? KaNamedClassSymbol ?: return null
  val visibility = createUseSiteVisibilityChecker(owner.containingKtFile.symbol, null, owner)
  if (!visibility.isVisible(annotationClass)) return null
  return mapKeyChoice(annotationClass, ownerSymbol, options)
}

private fun KaSession.existingMapKeyChoice(
  owner: KaNamedClassSymbol,
  options: MetroOptions,
): ContributionMapKeyChoice? {
  val existing =
    owner.annotations.firstOrNull { annotation ->
      val annotationClass = annotation.classId?.let(::findClass)
      annotationClass?.annotations?.any { it.classId in options.mapKeyAnnotations } == true
    } ?: return null
  val classId = existing.classId ?: return null
  val name = classId.shortClassName.asString()
  return ContributionMapKeyChoice(classId, "Use existing @$name", "", emptyList())
}

/** Import and type aliases are followed through source files containing their indexed names. */
private fun KaSession.mapKeyCandidateIds(
  owner: KtClassOrObject,
  metaAnnotations: Set<ClassId>,
): Set<ClassId> {
  val project = owner.project
  val searchScope = owner.resolveScope
  val sourceScope = searchScope.intersectWith(GlobalSearchScope.projectScope(project))
  val pendingNames = ArrayDeque(metaAnnotations.map { it.shortClassName.asString() })
  val searchedNames = hashSetOf<String>()
  val visitedFiles = hashSetOf<KtFile>()
  val candidates = linkedSetOf<ClassId>()

  fun visitFile(file: KtFile) {
    if (!visitedFiles.add(file)) return
    file.accept(
      object : KtTreeVisitorVoid() {
        override fun visitClass(klass: KtClass) {
          ProgressManager.checkCanceled()
          if (klass.isAnnotation()) klass.getClassId()?.let(candidates::add)
          super.visitClass(klass)
        }

        override fun visitTypeAlias(typeAlias: KtTypeAlias) {
          ProgressManager.checkCanceled()
          val expanded = typeAlias.getTypeReference()?.type?.fullyExpandedType as? KaClassType
          if (expanded?.classId in metaAnnotations) {
            typeAlias.name?.let(pendingNames::addLast)
          }
          super.visitTypeAlias(typeAlias)
        }
      }
    )
  }

  visitFile(owner.containingKtFile)
  val searchHelper = PsiSearchHelper.getInstance(project)
  while (pendingNames.isNotEmpty()) {
    ProgressManager.checkCanceled()
    val name = pendingNames.removeFirst()
    if (!searchedNames.add(name)) continue
    for (entry in KotlinAnnotationsIndex[name, project, searchScope]) {
      ProgressManager.checkCanceled()
      val klass = PsiTreeUtil.getParentOfType(entry, KtClass::class.java)
      if (klass?.isAnnotation() == true) klass.getClassId()?.let(candidates::add)
    }
    val files = linkedSetOf<KtFile>()
    searchHelper.processElementsWithWord(
      { element, _ ->
        ProgressManager.checkCanceled()
        (element.containingFile as? KtFile)?.let(files::add)
        true
      },
      sourceScope,
      name,
      UsageSearchContext.IN_CODE,
      true,
    )
    files.forEach(::visitFile)
  }
  return candidates
}

private fun KaSession.mapKeyChoice(
  annotationClass: KaNamedClassSymbol,
  owner: KaNamedClassSymbol,
  options: MetroOptions,
): ContributionMapKeyChoice? {
  if (annotationClass.classKind != KaClassKind.ANNOTATION_CLASS) return null
  if (annotationClass.typeParameters.isNotEmpty()) return null
  val meta =
    annotationClass.annotations.firstOrNull { it.classId in options.mapKeyAnnotations }
      ?: return null
  if (!annotationClass.supportsClassContribution()) return null
  val parameters =
    annotationClass.memberScope.constructors.firstOrNull { it.isPrimary }?.valueParameters
      ?: return null
  if (parameters.isEmpty()) return null
  if (meta.booleanArgument("unwrapValue") != false) {
    if (parameters.size != 1) return null
    val typeId = (parameters.single().returnType.fullyExpandedType as? KaClassType)?.classId
    val isArray =
      typeId == StandardClassIds.Array ||
        typeId in StandardClassIds.primitiveArrayTypeByElementType.values
    if (isArray) return null
  }
  val classId = annotationClass.classId ?: return null
  if (meta.booleanArgument("implicitClassKey") == true) {
    val parameter = parameters.singleOrNull() ?: return null
    val typeId = (parameter.returnType.fullyExpandedType as? KaClassType)?.classId
    if (typeId != StandardClassIds.KClass || !parameter.hasDefaultValue) return null
    // Compiled annotations retain the implicit-key contract and the presence of a default.
    // Their default expression is absent from Kotlin metadata. Source declarations can be
    // checked directly while they are being authored.
    if (annotationClass.origin != KaSymbolOrigin.LIBRARY) {
      val default =
        (parameter.psi as? KtParameter)?.defaultValue as? KtClassLiteralExpression ?: return null
      val defaultType =
        default.receiverExpression?.expressionType?.fullyExpandedType as? KaClassType
      if (defaultType?.classId != StandardClassIds.Nothing) return null
    }
  }

  val arguments = mutableListOf<String>()
  val editable = mutableListOf<String>()
  for (parameter in parameters) {
    ProgressManager.checkCanceled()
    // An implicit class key keeps its default. Older runtimes with a required ClassKey value
    // still receive an explicit class literal from the ordinary required-argument path.
    if (parameter.hasDefaultValue) continue
    val value = mapKeyArgument(parameter.returnType, owner) ?: return null
    arguments += "${parameter.name.render()} = $value"
    editable += parameter.name.asString()
  }
  val suffix = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "(", ")")
  val name = classId.asSingleFqName().pathSegments().joinToString(".") { it.render() }
  return ContributionMapKeyChoice(
    classId = classId,
    label = "@${classId.relativeClassName.asString()} (${classId.packageFqName.asString()})",
    annotationText = "@$name$suffix",
    editableArguments = editable,
  )
}

/** An annotation copied to a generated binding must also support functions. */
private fun KaNamedClassSymbol.supportsClassContribution(): Boolean {
  val target =
    annotations.firstOrNull { it.classId == StandardClassIds.Annotations.Target } ?: return true
  val values = target.arguments.flatMap { it.expression.enumNames() }.toSet()
  return "CLASS" in values && "FUNCTION" in values
}

private fun KaAnnotationValue.enumNames(): List<String> =
  when (this) {
    is KaAnnotationValue.EnumEntryValue -> listOfNotNull(callableId?.callableName?.asString())
    is KaAnnotationValue.ArrayValue -> values.flatMap { it.enumNames() }
    else -> emptyList()
  }

private fun KaAnnotation.booleanArgument(name: String): Boolean? =
  (arguments.firstOrNull { it.name.asString() == name }?.expression
      as? KaAnnotationValue.ConstantValue)
    ?.value
    ?.value as? Boolean

private fun KaSession.mapKeyArgument(type: KaType, owner: KaNamedClassSymbol): String? {
  val expanded = type.fullyExpandedType as? KaClassType ?: return null
  return when (expanded.classId) {
    StandardClassIds.String -> "\"key\""
    StandardClassIds.Boolean -> "false"
    StandardClassIds.Byte,
    StandardClassIds.Short,
    StandardClassIds.Int -> "0"
    StandardClassIds.Long -> "0L"
    StandardClassIds.Float -> "0.0f"
    StandardClassIds.Double -> "0.0"
    StandardClassIds.Char -> "'a'"
    StandardClassIds.KClass -> classKeyArgument(expanded, owner)
    else -> {
      val enumClass = findClass(expanded.classId) as? KaNamedClassSymbol ?: return null
      if (enumClass.classKind != KaClassKind.ENUM_CLASS) return null
      val entry =
        enumClass.staticDeclaredMemberScope.callables
          .filterIsInstance<KaEnumEntrySymbol>()
          .firstOrNull() ?: return null
      entry.callableId?.asSingleFqName()?.pathSegments()?.joinToString(".") { it.render() }
    }
  }
}

/** Class literals erase type arguments, so each fallback is checked with star projections. */
@OptIn(KaExperimentalApi::class)
private fun KaSession.classKeyArgument(type: KaClassType, owner: KaNamedClassSymbol): String? {
  fun accepts(candidate: KaType): Boolean {
    val literalType = buildClassType(StandardClassIds.KClass) { argument(candidate) }
    return literalType.isSubtypeOf(type)
  }

  val classId =
    if (accepts(owner.defaultType)) {
      owner.classId ?: return null
    } else {
      val bound =
        type.typeArguments.singleOrNull()?.type?.fullyExpandedType as? KaClassType ?: return null
      val literalBound =
        buildClassType(bound.classId) {
          repeat(bound.typeArguments.size) {
            ProgressManager.checkCanceled()
            argument(buildStarTypeProjection())
          }
        }
      if (!accepts(literalBound)) return null
      bound.classId
    }
  val name = classId.asSingleFqName().pathSegments().joinToString(".") { it.render() }
  return "$name::class"
}
