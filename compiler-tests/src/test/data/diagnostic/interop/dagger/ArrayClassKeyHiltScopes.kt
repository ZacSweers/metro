// RENDER_DIAGNOSTICS_FULL_TEXT
// TARGET_BACKEND: JVM_IR
// ENABLE_HILT_INTEROP

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(<!KNOWN_KOTLINC_BUG_WARNING!>Array<String>::class<!>)
class ArrayComponentModule

@Module
@InstallIn(IntArray::class)
class PrimitiveArrayComponentModule

@Module
@InstallIn(SingletonComponent::class)
class OrdinaryComponentModule
