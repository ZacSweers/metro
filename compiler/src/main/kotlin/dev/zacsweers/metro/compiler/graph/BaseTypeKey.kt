package dev.zacsweers.metro.compiler.graph

internal interface BaseTypeKey<Type, Qualifier, Subtype : BaseTypeKey<Type, Qualifier, Subtype>> :
  Comparable<Subtype> {
  val type: Type
  val qualifier: Qualifier?

  fun copy(type: Type = this.type, qualifier: Qualifier? = this.qualifier): Subtype

  fun render(short: Boolean, includeQualifier: Boolean = true): String
}
