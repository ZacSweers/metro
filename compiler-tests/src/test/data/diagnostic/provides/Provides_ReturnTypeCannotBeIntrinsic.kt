// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import javax.inject.Provider as JavaxProvider
import jakarta.inject.Provider as JakartaProvider

typealias AliasedProvider = Provider<String>

class MyClass

interface ExampleGraph {
  val string: String
  val myClass: MyClass

  // Metro Provider is an intrinsic type
  @Provides fun provideProvider(): <!INTRINSIC_BINDING_ERROR!>Provider<String><!> = provider { "" }

  // javax.inject.Provider is an intrinsic type
  @Provides fun provideJavaxProvider(): <!INTRINSIC_BINDING_ERROR!>JavaxProvider<String><!> = JavaxProvider { "" }

  // jakarta.inject.Provider is an intrinsic type
  @Provides fun provideJakartaProvider(): <!INTRINSIC_BINDING_ERROR!>JakartaProvider<String><!> = JakartaProvider { "" }

  // kotlin.Lazy is an intrinsic type
  @Provides fun provideLazy(): <!INTRINSIC_BINDING_ERROR!>Lazy<String><!> = lazy { "" }

  // Nullable wrappers are also rejected
  @Provides fun provideNullable(): <!INTRINSIC_BINDING_ERROR!>Provider<String>?<!> = null

  // Type aliases that resolve to an intrinsic are rejected
  @Provides fun provideAliased(): <!INTRINSIC_BINDING_ERROR!>AliasedProvider<!> = provider { "" }

  // With `enableFunctionProviders` enabled (the default), Function0 is an intrinsic type
  @Provides fun provideFunction(): <!INTRINSIC_BINDING_ERROR!>() -> String<!> = { "" }
}

// Companion object @Provides is also checked
@DependencyGraph
interface GraphWithCompanion {
  val string: String

  companion object {
    @Provides fun provideString(): <!INTRINSIC_BINDING_ERROR!>Provider<String><!> = provider { "" }
  }
}

// @IntoSet / @IntoMap contributions returning an intrinsic are rejected — there's no
// multibinding carve-out.
interface MultibindingGraph {
  @Provides @IntoSet fun provideString(): <!INTRINSIC_BINDING_ERROR!>Provider<String><!> = provider { "" }
}
