# Metro Compiler Compatibility Layer

This module provides a compatibility layer for Metro's compiler plugin to work across different Kotlin compiler versions. As the Kotlin compiler APIs evolve and change between versions, this layer abstracts away version-specific differences.

## Overview

The Kotlin compiler plugin APIs are not stable and can change between versions. Some APIs get deprecated, renamed, or removed entirely. This compatibility layer provides a uniform interface (`FirCompatContext`) that Metro's compiler can use regardless of the underlying Kotlin version.

## IDE Plugin

The Kotlin IDE plugin bundles its own compiler copy, and can be checked at `lib/kotlinc.kotlin-compiler-common.jar/META-INF/compiler.version`.

## Architecture

### Core Interface

The `FirCompatContext` interface defines the contract for version-specific operations:

```kotlin
interface FirCompatContext {
  interface Factory {
    val kotlinVersion: String
    fun create(): FirCompatContext
  }
  
  // Version-abstracted methods
  fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>?
  fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>?
  fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>?
}
```

### Version-Specific Implementations

Each supported Kotlin version has its own module with a corresponding implementation:

- `k2220/` - Kotlin 2.2.20 compatibility
- `k230_dev9673/` - Kotlin 2.3.0-dev-9673 compatibility

Each module contains:
- `FirCompatContextImpl` - Version-specific implementation
- `Factory` - Creates instances for that Kotlin version
- Service loader configuration in `META-INF/services/`

### Service Discovery

The compatibility layer uses Java's `ServiceLoader` mechanism to discover available implementations at runtime. This allows Metro to automatically select the appropriate implementation based on the available Kotlin version.

## Adding Support for New Kotlin Versions

### Automatic Generation

Use the provided script to generate a skeleton for a new Kotlin version:

```bash
cd compiler-compat
./generate-compat-module.sh 2.4.0-Beta1
```

This will create:
- Module directory structure (`k240_Beta1/`)
- Build configuration files
- Skeleton implementation with TODOs
- Service loader configuration

### Manual Steps After Generation

1. **Add to build configuration:**
   ```kotlin
   // settings.gradle.kts
   include(":compiler-compat:k240_Beta1")
   
   // compiler/build.gradle.kts
   dependencies {
     implementation(project(":compiler-compat:k240_Beta1"))
   }
   ```

2. **Implement the compatibility methods:**
   Edit the generated `FirCompatContextImpl.kt` and replace the `TODO()` calls with actual implementations based on the available APIs in that Kotlin version.

3. **Test the implementation:**
   Run the compiler tests with the new Kotlin version to ensure compatibility.

### Version Naming Convention

The script automatically converts Kotlin versions to valid JVM package names:

- Dots are removed: `2.3.0` → `230`
- Dashes become underscores: `2.3.0-dev-9673` → `230_dev_9673`
- Module name gets `k` prefix: `k230_dev_9673`

Examples:
- `2.3.20` → `k2320`
- `2.4.0-Beta1` → `k240_Beta1`
- `2.5.0-dev-1234` → `k250_dev_1234`

## Common API Changes

### Kotlin 2.2.x → 2.3.x

The `getContainingClassSymbol` and `getContainingSymbol` extension functions were moved from `org.jetbrains.kotlin.fir.analysis.checkers` to `org.jetbrains.kotlin.fir.resolve.providers.firProvider`.

**Kotlin 2.2.x:**
```kotlin
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingSymbol

// Direct extension function calls
symbol.getContainingClassSymbol()
callableSymbol.getContainingSymbol(session)
```

**Kotlin 2.3.x:**
```kotlin
import org.jetbrains.kotlin.fir.resolve.providers.firProvider

// Via firProvider
session.firProvider.getContainingClass(symbol)
session.firProvider.getFirCallableContainerFile(callableSymbol)?.symbol
```

## Runtime Selection

Metro's compiler plugin uses `ServiceLoader` to discover and select the appropriate compatibility implementation at runtime:

```kotlin
val factory = ServiceLoader.load(FirCompatContext.Factory::class.java)
  .find { it.kotlinVersion == currentKotlinVersion }
  ?: error("No compatibility implementation found for Kotlin $currentKotlinVersion")

val compatContext = factory.create()
```

This allows Metro to support multiple Kotlin versions without requiring separate builds or complex version detection logic.

## Development Notes

- Always implement all interface methods, even if some are no-ops for certain versions
- Include comprehensive KDoc comments explaining version-specific behavior
- Test thoroughly with the target Kotlin version before releasing
- Keep implementations focused and minimal - avoid adding version-specific extensions beyond the interface contract