/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.lattice.compiler.fir.copyParameters
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.wrapInProviderIfNecessary
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameterCopy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
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
          it.wrapInProviderIfNecessary(session),
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
          valueParameter.name,
          valueParameter.contextKey.typeKey.type.wrapInProviderIfNecessary(session),
          key = LatticeKeys.ValueParameter,
        )
      }
    }
    .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
}

internal fun FirExtension.buildFactoryCreateFunction(
  context: MemberGenerationContext,
  returnType: ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<LatticeFirValueParameter>,
): FirNamedFunctionSymbol {
  return buildFactoryCreateFunction(
    context,
    { returnType },
    instanceReceiver,
    extensionReceiver,
    valueParameters,
  )
}

@OptIn(SymbolInternals::class)
internal fun FirExtension.buildFactoryCreateFunction(
  context: MemberGenerationContext,
  returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<LatticeFirValueParameter>,
): FirNamedFunctionSymbol {
  return generateMemberFunction(
      owner = context.owner,
      returnTypeProvider = returnTypeProvider,
      callableId = CallableId(context.owner.classId, LatticeSymbols.Names.create),
      origin = LatticeKeys.FactoryCreateFunction.origin,
    ) {
      val thisFunctionSymbol = symbol

      val ownerToCopyTypeParametersFrom =
        if (context.owner.isCompanion) {
          context.owner.getContainingClassSymbol()!!
        } else {
          context.owner
        }
      for (typeParameter in ownerToCopyTypeParametersFrom.typeParameterSymbols) {
        typeParameters +=
          buildTypeParameterCopy(typeParameter.fir) {
            origin = LatticeKeys.Default.origin
            this.symbol = FirTypeParameterSymbol()
            containingDeclarationSymbol = thisFunctionSymbol
          }
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
            type = it.wrapInProviderIfNecessary(session).toFirResolvedTypeRef(),
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
          original.contextKey.typeKey.type.wrapInProviderIfNecessary(session).toFirResolvedTypeRef()
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
      origin = LatticeKeys.FactoryNewInstanceFunction.origin,
    ) {
      val thisFunctionSymbol = symbol

      val ownerToCopyTypeParametersFrom =
        if (context.owner.isCompanion) {
          context.owner.getContainingClassSymbol()!!
        } else {
          context.owner
        }
      for (typeParameter in ownerToCopyTypeParametersFrom.typeParameterSymbols) {
        typeParameters +=
          buildTypeParameterCopy(typeParameter.fir) {
            origin = LatticeKeys.Default.origin
            this.symbol = FirTypeParameterSymbol()
            containingDeclarationSymbol = thisFunctionSymbol
          }
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
        sourceParameters = valueParameters,
        // Will be copied in IR
        copyParameterDefaults = false,
      ) { original ->
        this.returnTypeRef = original.contextKey.originalType(session).toFirResolvedTypeRef()
      }
    }
    .symbol
}
