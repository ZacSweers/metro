// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryImpl
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.InjectedClassTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer.MemberInjectClass
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName

/** Read-only lookup interface for declarations produced during pass-1 transformations. */
internal interface MetroDeclarations {
  fun findBindingContainer(
    declaration: IrClass,
    fqName: FqName = declaration.kotlinFqName,
    graphProto: DependencyGraphProto? = null,
  ): BindingContainer?

  fun providerFactoriesFor(parent: IrClass): List<Pair<IrTypeKey, ProviderFactory>>

  fun lookupProviderFactory(binding: IrBinding.Provided): ProviderFactory?

  fun findClassFactory(
    declaration: IrClass,
    previouslyFoundConstructor: IrConstructor?,
    doNotErrorOnMissing: Boolean,
  ): ClassFactory?

  fun findInjector(declaration: IrClass): MemberInjectClass?

  fun findAllInjectorsFor(declaration: IrClass): List<MemberInjectClass>

  fun findAssistedFactoryImpl(declaration: IrClass): AssistedFactoryImpl
}

internal class DefaultMetroDeclarations(
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val injectedClassTransformer: InjectedClassTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
) : MetroDeclarations {

  override fun findBindingContainer(
    declaration: IrClass,
    fqName: FqName,
    graphProto: DependencyGraphProto?,
  ): BindingContainer? = bindingContainerTransformer.findContainer(declaration, fqName, graphProto)

  override fun providerFactoriesFor(parent: IrClass): List<Pair<IrTypeKey, ProviderFactory>> =
    bindingContainerTransformer.factoryClassesFor(parent)

  override fun lookupProviderFactory(binding: IrBinding.Provided): ProviderFactory? =
    bindingContainerTransformer.getOrLookupProviderFactory(binding)

  override fun findClassFactory(
    declaration: IrClass,
    previouslyFoundConstructor: IrConstructor?,
    doNotErrorOnMissing: Boolean,
  ): ClassFactory? =
    injectedClassTransformer.getOrGenerateFactory(
      declaration,
      previouslyFoundConstructor,
      doNotErrorOnMissing,
    )

  override fun findInjector(declaration: IrClass): MemberInjectClass? =
    membersInjectorTransformer.getOrGenerateInjector(declaration)

  override fun findAllInjectorsFor(declaration: IrClass): List<MemberInjectClass> =
    membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)

  override fun findAssistedFactoryImpl(declaration: IrClass): AssistedFactoryImpl =
    assistedFactoryTransformer.getOrGenerateImplClass(declaration)
}
