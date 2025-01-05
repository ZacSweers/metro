package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.lattice.compiler.fir.copyParameters
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.wrapInProvider
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameterCopy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name


internal fun FirExtension.buildFactoryConstructor(
  context: MemberGenerationContext,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<LatticeFirValueParameter>,
): FirConstructor {
  val owner = context.owner
  return createConstructor(
    owner,
    LatticeKeys.Default,
    isPrimary = true,
    generateDelegatedNoArgConstructorCall = true,
  ) {
    instanceReceiver?.let {
      valueParameter(LatticeSymbols.Names.instance, it, key = LatticeKeys.InstanceParameter)
    }
    extensionReceiver?.let {
      valueParameter(
        LatticeSymbols.Names.receiver,
        it.wrapInProvider(),
        key = LatticeKeys.ReceiverParameter,
      )
    }
    for (i in valueParameters.indices) {
      val valueParameter = valueParameters[i]
      // TODO toe-hold for later factory gen
      if (
        valueParameter.symbol.isAnnotatedWithAny(
          session,
          session.latticeClassIds.assistedAnnotations,
        )
      ) {
        continue
      }
      valueParameter(
        valueParameter.symbol.name,
        valueParameter.contextKey.typeKey.type.wrapInProvider(),
        key = LatticeKeys.ValueParameter,
      )
    }
  }
    .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
}

@OptIn(SymbolInternals::class)
internal fun FirExtension.buildFactoryCreateFunction(
  context: MemberGenerationContext,
  returnType: ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<LatticeFirValueParameter>,
): FirNamedFunctionSymbol {
  return generateMemberFunction(
    context.owner,
    returnType.toFirResolvedTypeRef(),
    CallableId(context.owner.classId, LatticeSymbols.Names.create),
  ) {
    val thisFunctionSymbol = symbol
    for (typeParameter in context.owner.typeParameterSymbols) {
      typeParameters += buildTypeParameterCopy(typeParameter.fir) {}
    }

    instanceReceiver?.let {
      this.valueParameters +=
        buildSimpleValueParameter(
          name = LatticeSymbols.Names.instance,
          type = it.toFirResolvedTypeRef(),
          containingFunctionSymbol = thisFunctionSymbol,
          origin = LatticeKeys.InstanceParameter.origin,
        )
    }
    extensionReceiver?.let {
      this.valueParameters +=
        buildSimpleValueParameter(
          name = LatticeSymbols.Names.receiver,
          type = it.wrapInProvider().toFirResolvedTypeRef(),
          containingFunctionSymbol = thisFunctionSymbol,
          origin = LatticeKeys.ReceiverParameter.origin,
        )
    }

    copyParameters(
      functionBuilder = this,
      sourceParameters =
        valueParameters.filterNot {
          it.symbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
        },
      // Will be copied in IR
      copyParameterDefaults = false,
    ) { original ->
      this.returnTypeRef =
        original.contextKey.typeKey.type.wrapInProvider().toFirResolvedTypeRef()
    }
  }
    .symbol
}

@OptIn(SymbolInternals::class)
internal fun FirExtension.buildNewInstanceFunction(
  context: MemberGenerationContext,
  name: Name,
  returnType: ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<LatticeFirValueParameter>,
): FirNamedFunctionSymbol {
  return generateMemberFunction(
    context.owner,
    returnType.toFirResolvedTypeRef(),
    CallableId(context.owner.classId, name),
    origin = LatticeKeys.ProviderNewInstanceFunction.origin,
  ) {
    val thisFunctionSymbol = symbol
    for (typeParameter in context.owner.typeParameterSymbols) {
      typeParameters += buildTypeParameterCopy(typeParameter.fir) {}
    }

    instanceReceiver?.let {
      this.valueParameters +=
        buildSimpleValueParameter(
          name = LatticeSymbols.Names.instance,
          type = it.toFirResolvedTypeRef(),
          containingFunctionSymbol = thisFunctionSymbol,
          origin = LatticeKeys.InstanceParameter.origin,
        )
    }
    extensionReceiver?.let {
      this.valueParameters +=
        buildSimpleValueParameter(
          name = LatticeSymbols.Names.receiver,
          type = it.toFirResolvedTypeRef(),
          containingFunctionSymbol = thisFunctionSymbol,
          origin = LatticeKeys.ReceiverParameter.origin,
        )
    }

    copyParameters(
      functionBuilder = this,
      sourceParameters =
        valueParameters.filterNot {
          it.symbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
        },
      // Will be copied in IR
      copyParameterDefaults = false,
    ) { original ->
      this.returnTypeRef = original.contextKey.originalType.toFirResolvedTypeRef()
    }
  }
    .symbol
}