// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import dagger.BindsOptionalOf
import java.util.Optional

@BindingContainer
interface Bindings {
  @BindsOptionalOf
  fun providerString(): <!BINDS_OPTIONAL_OF_ERROR!>Provider<String><!>

  @BindsOptionalOf
  fun lazyInt(): <!BINDS_OPTIONAL_OF_ERROR!>Lazy<Int><!>

  @BindsOptionalOf
  fun providerOfLazyLong(): <!BINDS_OPTIONAL_OF_ERROR!>Provider<Lazy<Long>><!>

  @BindsOptionalOf
  fun optionalOptional(): <!BINDS_OPTIONAL_OF_WARNING!>Optional<Boolean><!>
}
