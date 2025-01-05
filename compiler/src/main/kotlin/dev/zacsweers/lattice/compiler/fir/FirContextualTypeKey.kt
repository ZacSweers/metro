package dev.zacsweers.lattice.compiler.fir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeAnnotations
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.expectAs
import dev.zacsweers.lattice.compiler.expectAsOrNull
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.propertyIfAccessor
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId

@Poko
internal class FirContextualTypeKey(
  val typeKey: FirTypeKey,
  val isWrappedInProvider: Boolean = false,
  val isWrappedInLazy: Boolean = false,
  val isLazyWrappedInProvider: Boolean = false,
  val hasDefault: Boolean = false,
  val isDeferrable: Boolean = isWrappedInProvider || isWrappedInLazy || isLazyWrappedInProvider,
  val isIntoMultibinding: Boolean = false,
) {

  val requiresProviderInstance: Boolean =
    isWrappedInProvider || isLazyWrappedInProvider || isWrappedInLazy

  val originalType: ConeKotlinType
    get() =
      when {
        isLazyWrappedInProvider -> typeKey.type.wrapInLazy().wrapInProvider()
        isWrappedInProvider -> typeKey.type.wrapInProvider()
        isWrappedInLazy -> typeKey.type.wrapInLazy()
        else -> typeKey.type
      }

  override fun toString(): String = render(short = true)

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    val wrapperType =
      when {
        isWrappedInProvider -> "Provider"
        isWrappedInLazy -> "Lazy"
        isLazyWrappedInProvider -> "Provider<Lazy<"
        else -> null
      }
    if (wrapperType != null) {
      append(wrapperType)
      append("<")
    }
    append(typeKey.render(short, includeQualifier))
    if (wrapperType != null) {
      append(">")
      if (isLazyWrappedInProvider) {
        // One more bracket
        append(">")
      }
    }
    if (hasDefault) {
      append(" = ...")
    }
  }

  // TODO cache these?
  companion object {
    @OptIn(SymbolInternals::class)
    fun from(
      session: FirSession,
      function: FirFunctionSymbol<*>,
      annotations: LatticeAnnotations<LatticeFirAnnotation>,
      type: ConeKotlinType = function.resolvedReturnTypeRef.coneType,
    ): FirContextualTypeKey =
      type.asFirContextualTypeKey(
        session,
        function.fir.propertyIfAccessor.annotations.qualifierAnnotation(session),
        false,
        annotations.isIntoMultibinding,
      )

    fun from(
      session: FirSession,
      parameter: FirValueParameterSymbol,
      type: ConeKotlinType = parameter.resolvedReturnTypeRef.coneType,
    ): FirContextualTypeKey =
      type.asFirContextualTypeKey(
        session = session,
        qualifierAnnotation = parameter.annotations.qualifierAnnotation(session),
        hasDefault = parameter.hasDefaultValue,
        isIntoMultibinding = false,
      )
  }
}

internal fun ConeKotlinType.asFirContextualTypeKey(
  session: FirSession,
  qualifierAnnotation: LatticeFirAnnotation?,
  hasDefault: Boolean,
  isIntoMultibinding: Boolean,
): FirContextualTypeKey {
  val declaredType = this
  val rawClass = declaredType
  val rawClassId = rawClass.classId

  val isWrappedInProvider = rawClassId in session.latticeClassIds.providerTypes
  val isWrappedInLazy = rawClassId in session.latticeClassIds.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      declaredType.typeArguments[0].expectAsOrNull<ConeKotlinTypeProjection>()?.type?.classId in
        session.latticeClassIds.lazyTypes

  val type =
    when {
      isLazyWrappedInProvider ->
        declaredType.typeArguments[0]
          .expectAs<ConeKotlinTypeProjection>()
          .type
          .typeArguments
          .single()
          .expectAs<ConeKotlinTypeProjection>()
          .type
      isWrappedInProvider || isWrappedInLazy ->
        declaredType.typeArguments[0].expectAs<ConeKotlinTypeProjection>().type
      else -> declaredType
    }

  val isDeferrable =
    isLazyWrappedInProvider ||
      isWrappedInProvider ||
      isWrappedInLazy ||
      run {
        // Check if this is a Map<Key, Provider<Value>>
        // If it has no type args we can skip
        if (declaredType.typeArguments.size != 2) return@run false
        // TODO check implements instead?
        val isMap = rawClassId == LatticeSymbols.ClassIds.map
        if (!isMap) return@run false
        val valueTypeContextKey =
          declaredType.typeArguments[1]
            .expectAs<ConeKotlinTypeProjection>()
            .type
            .asFirContextualTypeKey(
              session,
              // TODO could we actually support these?
              qualifierAnnotation = null,
              hasDefault = false,
              isIntoMultibinding = false,
            )

        valueTypeContextKey.isDeferrable == true
      }

  val typeKey = FirTypeKey(type, qualifierAnnotation)
  return FirContextualTypeKey(
    typeKey = typeKey,
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    hasDefault = hasDefault,
    isDeferrable = isDeferrable,
    isIntoMultibinding = isIntoMultibinding,
  )
}
