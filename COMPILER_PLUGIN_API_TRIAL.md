# Kotlin compiler plugin API trial

Handoff recorded on November 5, 2026. This branch, `z/compilerPluginApiTrial`, preserves an unfinished migration experiment based on commit `b69da16a92c52922fcd10a50f3c368977102a1b9`. The normal Kotlin 2.4.20 build passes. Compiling against `kotlin-compiler-plugin-api:2.5.20-dev-2623` remains blocked by missing APIs.

## Purpose and current result

JetBrains is developing a smaller compiler dependency for third-party plugins. See [KT-90094](https://youtrack.jetbrains.com/issue/KT-90094) and the [bootstrap repository](https://redirector.kotlinlang.org/maven/bootstrap/org/jetbrains/kotlin/kotlin-compiler-plugin-api/). This artifact has no binary compatibility guarantees. The artifact version tested here was `2.5.20-dev-2623`, which was the available bootstrap publication when checked on November 4.

The first trial found 36 unresolved import paths specific to the reduced API artifact. Moving small utilities into Metro and using available lower-level APIs removed 14 of those paths. The remaining 22 are listed below. The isolated main-module compilation produced 378 diagnostics after the cleanup, down from 464. Many diagnostics cascade from the same missing declarations, so these totals do not represent independent API requests.

The full compiler at the same bootstrap version also produces four diagnostics representing three existing source incompatibilities. Those failures are separate from the reduced API contents:

- `fir/FirIdentities.kt` uses `ConeTypeParameterLookupTag.typeParameterSymbol`, which is unresolved.
- `fir/NonAcceptingFirAnnotationCall.kt` must implement `replaceSource(newSource: KtSourceElement?)`.
- `ir/graph/SyntheticGraphGenerator.kt` imports and calls the unavailable `org.jetbrains.kotlin.ir.interpreter.hasAnnotation`.

These paths are relative to `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/`. The trial leaves those incompatibilities unresolved.

## Changes preserved on this branch

`build.gradle.kts` adds the optional `metro.compilerApiDependency` property. It accepts a full Maven coordinate and substitutes `org.jetbrains.kotlin:kotlin-compiler` on the main `compileClasspath` of `:compiler`, `:compiler-compat`, and `:compiler-compat:k2520_dev_2623`. Older compatibility adapters keep their pinned compiler dependencies. Test and documentation runtime configurations retain the full compiler. The property does not change the Kotlin Gradle plugin version.

`.github/CONTRIBUTING.md` documents the trial commands. The normal build continues to use Kotlin 2.4.20 when the property is absent.

Small helpers were added to existing Metro utility files with JetBrains copyright attribution. Call sites now use those helpers, existing lower-level scope APIs, or annotation class IDs in `Symbols`. The removed upstream imports are:

```text
org.jetbrains.kotlin.backend.jvm.ir.fileParentOrNull
org.jetbrains.kotlin.backend.jvm.ir.isEffectivelyInlineOnly
org.jetbrains.kotlin.backend.jvm.ir.isWithFlexibleNullability
org.jetbrains.kotlin.backend.wasm.ir2wasm.allSuperInterfaces
org.jetbrains.kotlin.fir.analysis.checkers.classKind
org.jetbrains.kotlin.fir.analysis.checkers.declaredMemberScope
org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
org.jetbrains.kotlin.fir.analysis.checkers.findClosestClassOrObject
org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
org.jetbrains.kotlin.fir.backend.native.interop.parentsWithSelf
org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
org.jetbrains.kotlin.name.JsStandardClassIds
org.jetbrains.kotlin.name.JvmStandardClassIds
```

Preserve the behavior of these helpers when revisiting them. `directOverriddenSymbolsSafe` warms the function scope and requests the STATUS phase. `allSuperInterfaces` visits ancestors first, includes the receiver when it is an interface, and repeats shared ancestors for each inheritance path. `IrTest` covers that traversal with real IR declarations. The local `fileParentOrNull` returns null for nondeclaration parents; the core `fileOrNull` helper can cast such parents and throw.

The copied implementations were checked against official Kotlin sources at tag `build-2.5.20-dev-2623`, including [FirHelpers.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/fir/checkers/checkers.common/src/org/jetbrains/kotlin/fir/analysis/checkers/FirHelpers.kt), [FirObjCInterop.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/fir/fir-native/src/org/jetbrains/kotlin/fir/backend/native/interop/FirObjCInterop.kt), [JvmIrUtils.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/ir/backend.jvm/src/org/jetbrains/kotlin/backend/jvm/ir/JvmIrUtils.kt), [JvmIrInlineUtils.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/ir/backend.jvm/src/org/jetbrains/kotlin/backend/jvm/ir/JvmIrInlineUtils.kt), [IrJvmFlexibleType.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/ir/backend.jvm/src/org/jetbrains/kotlin/backend/jvm/ir/IrJvmFlexibleType.kt), and [ClassInfo.kt](https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-2623/compiler/ir/backend.wasm/src/org/jetbrains/kotlin/backend/wasm/ir2wasm/ClassInfo.kt).

## Reproduce the trial

Run commands from the repository root. Check the normal build first:

```bash
./gradlew :compiler:compileKotlin --quiet
```

Run the API-only compile. It currently stops in the shared compatibility module because `TypeResolutionConfiguration` is unavailable. The latest adapter also requires this type.

```bash
./gradlew :compiler:compileKotlin --quiet -Pmetro.compilerApiDependency=org.jetbrains.kotlin:kotlin-compiler-plugin-api:2.5.20-dev-2623
```

Use the full compiler at the same version as a control. It builds the compatibility modules, then fails in the main compiler module on the three source incompatibilities described above. After that command, exclude the compatibility compilation tasks to collect the main module's API errors:

```bash
./gradlew :compiler:compileKotlin --quiet -Pmetro.compilerApiDependency=org.jetbrains.kotlin:kotlin-compiler:2.5.20-dev-2623
./gradlew :compiler:compileKotlin --quiet -Pmetro.compilerApiDependency=org.jetbrains.kotlin:kotlin-compiler-plugin-api:2.5.20-dev-2623 -x :compiler-compat:compileKotlin -x :compiler-compat:k2520_dev_2623:compileKotlin
```

The second command reuses compatibility classes compiled against the full compiler. This is a diagnostic technique. A completed migration must compile every affected module against the API artifact without task exclusions. The main module's dependency report was checked during the trial and contained no full compiler dependency on its substituted compile classpath.

To inspect the latest adapter independently after the full-compiler control:

```bash
./gradlew :compiler-compat:k2520_dev_2623:compileKotlin --quiet -Pmetro.compilerApiDependency=org.jetbrains.kotlin:kotlin-compiler-plugin-api:2.5.20-dev-2623 -x :compiler-compat:compileKotlin
```

The existing `kotlin_repo_url` property can select a local Maven repository. The following command is documented for JetBrains developers, but `2.5.255-SNAPSHOT` was unavailable locally and this configuration has not been tested:

```bash
./gradlew :compiler:compileKotlin --quiet -Pmetro.compilerApiDependency=org.jetbrains.kotlin:kotlin-compiler-plugin-api:2.5.255-SNAPSHOT -Pkotlin_repo_url="file://$HOME/.m2/repository"
```

Run the normal compilation again after diagnostic probes to restore the default outputs.

## Remaining upstream API requests

| Unresolved import | Why Metro uses it |
| --- | --- |
| `org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration` | Configures type resolution for an annotation's source file. |
| `org.jetbrains.kotlin.fir.resolve.typeResolver` | Resolves cross-file annotation class references, including contribution replacements. |
| `org.jetbrains.kotlin.fir.scopes.createImportingScopes` | Applies the source file's imports during cross-file type resolution. |
| `org.jetbrains.kotlin.fir.analysis.checkers.getAllowedAnnotationTargets` | Determines whether injection annotations can target a class or its constructor. |
| `org.jetbrains.kotlin.fir.backend.Fir2IrComponents` | Provides FIR session and conversion access for contributions and Hilt package enumeration. |
| `org.jetbrains.kotlin.fir.backend.FirMetadataSource` | Connects IR declarations to their FIR attributes. |
| `org.jetbrains.kotlin.fir.backend.toIrType` | Converts contribution annotation types into IR binding types. |
| `org.jetbrains.kotlin.fir.lazy.AbstractFir2IrLazyDeclaration` | Reads dependency annotations through FIR and identifies external parents. |
| `org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression` | Builds the `DeprecationLevel.HIDDEN` enum expression on generated declarations. |
| `org.jetbrains.kotlin.fir.java.FirCliSession` | Distinguishes CLI compilation from IDE analysis. |
| `org.jetbrains.kotlin.fir.java.JavaTypeParameterStack` | Supplies the context for resolving Java field types. |
| `org.jetbrains.kotlin.fir.java.declarations.FirJavaField` | Supports generated parameters for Java field injection. |
| `org.jetbrains.kotlin.fir.java.resolveIfJavaType` | Resolves Java field types before constructing binding keys. |
| `org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities` | Preserves Java package and protected visibility during code generation. |
| `org.jetbrains.kotlin.backend.jvm.ir.getIoFile` | Obtains source file paths for incremental dependency tracking. |
| `org.jetbrains.kotlin.backend.jvm.ir.getKtFile` | Obtains source PSI and checks `doNotAnalyze` for incremental lookups. |
| `org.jetbrains.kotlin.backend.jvm.ir.getJvmNameFromAnnotation` | Determines provider names for Dagger factory compatibility. |
| `org.jetbrains.kotlin.platform.jvm.isJvm` | Selects JVM-specific compiler behavior. |
| `org.jetbrains.kotlin.platform.konan.isNative` | Selects Native-specific compiler behavior. |
| `org.jetbrains.kotlin.js.config.jsIncrementalCompilationEnabled` | Applies Metro's version-dependent JS incremental compilation guard. |
| `org.jetbrains.kotlin.js.config.wasmCompilation` | Exempts Wasm compilation from the JS incremental compilation restriction. |
| `com.intellij.openapi.util.TextRange` | Represents PSI-based diagnostic highlight ranges. |

The compiler also reports inaccessible PSI supertypes, including `com.intellij.psi.PsiElement`, `NavigatablePsiElement`, `PsiClassOwner`, `PsiNamedElement`, and `com.intellij.extapi.psi.PsiFileBase`. These are additional dependencies exposed through Kotlin PSI classes and are outside the 22-import count.

Compiler-owned FIR services and types need their actual compiler implementations. JS configuration wrappers also depend on the compiler's original `JSConfigurationKeys` instances. Recreating key objects would fail to retrieve existing values. Copying `isJvm` would still require the missing `JvmPlatform` type. The file accessors depend on `PsiIrFileEntry` and PSI.

The larger helpers were left for later review. `getJvmNameFromAnnotation` handles `JvmExposeBoxed`, default stubs, and inline state-machine declarations. `getAllowedAnnotationTargets` includes lazy resolution, evaluated annotation arguments, and Java annotation defaults. `toQualifiedPropertyAccessExpression` handles enum symbol resolution, error references, and receiver construction.

## Upstream discussion and open questions

Metro's report is [KT-90954](https://youtrack.jetbrains.com/issue/KT-90954/Compiler-Plugin-API-Unresolved-references-using-kotlin-compiler-plugin-api). In the [November 5 comment](https://youtrack.jetbrains.com/issue/KT-90954/Compiler-Plugin-API-Unresolved-references-using-kotlin-compiler-plugin-api#focus=Comments-27-14614399.0-0), Youssef Shoaib pointed to [KT-91047](https://youtrack.jetbrains.com/issue/KT-91047) for `isJvm` and `isNative` and suggested `org.jetbrains.kotlin.platform.isNative` as an available alternative.

That common `isNative` helper exists in the bootstrap source. Switching Metro's imports to it failed compilation against the current Kotlin 2.4.20 dependency. Both imports were restored. Recheck its availability against the supported compilation baseline before attempting that replacement again.

Youssef also asked about IntelliJ class relocation in `kotlin-compiler-embeddable`. Metro compiles against the unshaded `kotlin-compiler`, and its internal compiler tests use the full compiler. The compiler shadow configuration does not relocate `com.intellij`. PSI-specific paths are guarded by the source representation: diagnostic spans inspect `KtPsiSourceElement`, and incremental tracking uses available Kotlin PSI. Their behavior under the embeddable compiler was not established by this trial. Investigate that runtime question separately before claiming those paths are compatible. A draft response was prepared; this workflow did not post it.

The report's introductory count still said 36 when last read, while its detailed list reflected the remaining 22 imports and PSI dependencies. Recheck the live discussion before preparing another response.

## Validation completed

Normal `:compiler:compileKotlin` passed after the helper changes and after all diagnostic probes. Nine `IrTest` tests passed, including the new interface traversal test:

```bash
./gradlew :compiler:compileKotlin :compiler:test --tests 'dev.zacsweers.metro.compiler.ir.IrTest' --quiet
```

Eleven focused compiler tests passed on Kotlin 2.4.20 and again with `-Pmetro.testCompilerVersion=2.5.20-dev-2623`:

```bash
./gradlew :compiler-tests:test \
  --tests 'dev.zacsweers.metro.compiler.DiagnosticTestGenerated*testCreateDynamicGraphCannotBeInLocalClasses' \
  --tests 'dev.zacsweers.metro.compiler.DiagnosticTestGenerated*testInlineProviderDiagnostics' \
  --tests 'dev.zacsweers.metro.compiler.BoxTestGenerated*testContributesToTypeAliasScope*' \
  --tests 'dev.zacsweers.metro.compiler.BoxTestGenerated*testContributesBindingBoundTypeTypeAlias' \
  --tests 'dev.zacsweers.metro.compiler.BoxTestGenerated*testOverrideCompatibleAccessors*' \
  --tests 'dev.zacsweers.metro.compiler.BoxTestGenerated*testCircuitFactoryInvocationWithSubtypeScreen' \
  --tests 'dev.zacsweers.metro.compiler.BoxTestGenerated*testInlinedProvidesAcrossModulesBytecodeCheck' \
  --tests 'dev.zacsweers.metro.compiler.ContributionProvidersBoxTestGenerated*testNestedContributedInterfaceDoesNotPromoteContainingClass' \
  --quiet
```

These bootstrap runtime tests use Metro compiled with Kotlin 2.4.20 and its compatibility adapters. They do not establish that Metro's sources compile against either bootstrap compiler artifact.

Two focused JS tests passed on Kotlin 2.4.20:

```bash
./gradlew :compiler-tests:test \
  --tests 'dev.zacsweers.metro.compiler.JsBoxTestGenerated*testContributesToTypeAliasScope' \
  --tests 'dev.zacsweers.metro.compiler.JsBoxTestGenerated*testNestedContributedInterfaceDoesNotPromoteContainingClass' \
  --quiet
```

The complete suite, older supported compiler versions, and API-only runtime behavior were not tested. Local investigation logs are under the ignored `build/reports/compiler-plugin-api/` directory, with the utility pass under `utilities/`. Those files are not part of this branch. This document preserves the findings needed to resume without them.

## Suggested next steps

1. Read the current upstream report and bootstrap metadata, then update the trial coordinate and latest compatibility-module selection if necessary.
2. Repeat the normal build, the full-compiler control, and the API compilation. Keep source incompatibilities and missing artifact contents accounted for separately.
3. Reassess the 22 imports against the new artifact and review the PSI relocation question. Preserve the supported baseline when replacing helpers such as `isNative`.
4. Once the affected modules compile without exclusions, run focused compiler tests and downstream validation for the actual API-built plugin. The successful runtime tests recorded above cover only the existing full-compiler build.
