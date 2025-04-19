

package dev.zacsweers.metro.compiler;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link dev.zacsweers.metro.compiler.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler-tests/src/test/data/diagnostic")
@TestDataPath("$PROJECT_ROOT")
public class DiagnosticTestGenerated extends AbstractDiagnosticTest {
  @Test
  public void testAllFilesPresentInDiagnostic() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-tests/src/test/data/diagnostic"), Pattern.compile("^(.+)\\.kt$"), null, true);
  }

  @Nested
  @TestMetadata("compiler-tests/src/test/data/diagnostic/createGraph")
  @TestDataPath("$PROJECT_ROOT")
  public class CreateGraph {
    @Test
    public void testAllFilesPresentInCreateGraph() {
      KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-tests/src/test/data/diagnostic/createGraph"), Pattern.compile("^(.+)\\.kt$"), null, true);
    }

    @Test
    @TestMetadata("CreateGraph_GraphHasFactory.kt")
    public void testCreateGraph_GraphHasFactory() {
      runTest("compiler-tests/src/test/data/diagnostic/createGraph/CreateGraph_GraphHasFactory.kt");
    }

    @Test
    @TestMetadata("CreateGraph_MustBeGraph.kt")
    public void testCreateGraph_MustBeGraph() {
      runTest("compiler-tests/src/test/data/diagnostic/createGraph/CreateGraph_MustBeGraph.kt");
    }

    @Test
    @TestMetadata("CreateGraph_MustBeGraphFactory.kt")
    public void testCreateGraph_MustBeGraphFactory() {
      runTest("compiler-tests/src/test/data/diagnostic/createGraph/CreateGraph_MustBeGraphFactory.kt");
    }

    @Test
    @TestMetadata("CreateGraph_OkCase.kt")
    public void testCreateGraph_OkCase() {
      runTest("compiler-tests/src/test/data/diagnostic/createGraph/CreateGraph_OkCase.kt");
    }
  }

  @Nested
  @TestMetadata("compiler-tests/src/test/data/diagnostic/provides")
  @TestDataPath("$PROJECT_ROOT")
  public class Provides {
    @Test
    public void testAllFilesPresentInProvides() {
      KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-tests/src/test/data/diagnostic/provides"), Pattern.compile("^(.+)\\.kt$"), null, true);
    }

    @Test
    @TestMetadata("BindsNonThisReturningBodiesShouldError_AbstractClass.kt")
    public void testBindsNonThisReturningBodiesShouldError_AbstractClass() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/BindsNonThisReturningBodiesShouldError_AbstractClass.kt");
    }

    @Test
    @TestMetadata("BindsNonThisReturningBodiesShouldError_Interface.kt")
    public void testBindsNonThisReturningBodiesShouldError_Interface() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/BindsNonThisReturningBodiesShouldError_Interface.kt");
    }

    @Test
    @TestMetadata("BindsWithBodiesShouldBePrivate_InAbstractClass.kt")
    public void testBindsWithBodiesShouldBePrivate_InAbstractClass() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/BindsWithBodiesShouldBePrivate_InAbstractClass.kt");
    }

    @Test
    @TestMetadata("BindsWithBodiesShouldBePrivate_InInterface.kt")
    public void testBindsWithBodiesShouldBePrivate_InInterface() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/BindsWithBodiesShouldBePrivate_InInterface.kt");
    }

    @Test
    @TestMetadata("Binds_Interface_OkCase.kt")
    public void testBinds_Interface_OkCase() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Binds_Interface_OkCase.kt");
    }

    @Test
    @TestMetadata("Binds_interface_BoundTypesMustBeSubtypes.kt")
    public void testBinds_interface_BoundTypesMustBeSubtypes() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Binds_interface_BoundTypesMustBeSubtypes.kt");
    }

    @Test
    @TestMetadata("Binds_interface_SameTypesCannotHaveSameQualifiers.kt")
    public void testBinds_interface_SameTypesCannotHaveSameQualifiers() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Binds_interface_SameTypesCannotHaveSameQualifiers.kt");
    }

    @Test
    @TestMetadata("Binds_interface_ShouldNotHaveBodies.kt")
    public void testBinds_interface_ShouldNotHaveBodies() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Binds_interface_ShouldNotHaveBodies.kt");
    }

    @Test
    @TestMetadata("DaggerReusable_IsUnsupported.kt")
    public void testDaggerReusable_IsUnsupported() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/DaggerReusable_IsUnsupported.kt");
    }

    @Test
    @TestMetadata("PrivateProviderOption_Error.kt")
    public void testPrivateProviderOption_Error() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/PrivateProviderOption_Error.kt");
    }

    @Test
    @TestMetadata("PrivateProviderOption_None.kt")
    public void testPrivateProviderOption_None() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/PrivateProviderOption_None.kt");
    }

    @Test
    @TestMetadata("ProvidedInjectedClassesWithMatchingTypeKeysAreREportedAsWarnings_Qualified.kt")
    public void testProvidedInjectedClassesWithMatchingTypeKeysAreREportedAsWarnings_Qualified() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidedInjectedClassesWithMatchingTypeKeysAreREportedAsWarnings_Qualified.kt");
    }

    @Test
    @TestMetadata("ProvidedInjectedClassesWithMatchingTypeKeysAreReportedAsWarnings.kt")
    public void testProvidedInjectedClassesWithMatchingTypeKeysAreReportedAsWarnings() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidedInjectedClassesWithMatchingTypeKeysAreReportedAsWarnings.kt");
    }

    @Test
    @TestMetadata("ProvidedInjectedClassesWithMatchingTypeKeysButDifferentScopesAreOk.kt")
    public void testProvidedInjectedClassesWithMatchingTypeKeysButDifferentScopesAreOk() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidedInjectedClassesWithMatchingTypeKeysButDifferentScopesAreOk.kt");
    }

    @Test
    @TestMetadata("ProvidesCannotHaveReceivers_AbstractClass.kt")
    public void testProvidesCannotHaveReceivers_AbstractClass() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesCannotHaveReceivers_AbstractClass.kt");
    }

    @Test
    @TestMetadata("ProvidesCannotHaveReceivers_Interface.kt")
    public void testProvidesCannotHaveReceivers_Interface() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesCannotHaveReceivers_Interface.kt");
    }

    @Test
    @TestMetadata("ProvidesCannotLiveInObjects.kt")
    public void testProvidesCannotLiveInObjects() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesCannotLiveInObjects.kt");
    }

    @Test
    @TestMetadata("ProvidesFunctionsCannotBeTopLevel.kt")
    public void testProvidesFunctionsCannotBeTopLevel() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesFunctionsCannotBeTopLevel.kt");
    }

    @Test
    @TestMetadata("ProvidesMustHaveABody_AbstractClass.kt")
    public void testProvidesMustHaveABody_AbstractClass() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesMustHaveABody_AbstractClass.kt");
    }

    @Test
    @TestMetadata("ProvidesMustHaveABody_Interface.kt")
    public void testProvidesMustHaveABody_Interface() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesMustHaveABody_Interface.kt");
    }

    @Test
    @TestMetadata("ProvidesMustHaveExplicitReturnTypes.kt")
    public void testProvidesMustHaveExplicitReturnTypes() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesMustHaveExplicitReturnTypes.kt");
    }

    @Test
    @TestMetadata("ProvidesPropertiesCannotBeMutable.kt")
    public void testProvidesPropertiesCannotBeMutable() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesPropertiesCannotBeMutable.kt");
    }

    @Test
    @TestMetadata("ProvidesShouldBePrivate_InAbstractClass.kt")
    public void testProvidesShouldBePrivate_InAbstractClass() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesShouldBePrivate_InAbstractClass.kt");
    }

    @Test
    @TestMetadata("ProvidesShouldBePrivate_InInterface.kt")
    public void testProvidesShouldBePrivate_InInterface() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesShouldBePrivate_InInterface.kt");
    }

    @Test
    @TestMetadata("ProvidesWithExtensionsAndNonThisReturningBodiesShouldError.kt")
    public void testProvidesWithExtensionsAndNonThisReturningBodiesShouldError() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/ProvidesWithExtensionsAndNonThisReturningBodiesShouldError.kt");
    }

    @Test
    @TestMetadata("Provides_AbstractClass_MayNotHaveTypeParameters.kt")
    public void testProvides_AbstractClass_MayNotHaveTypeParameters() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Provides_AbstractClass_MayNotHaveTypeParameters.kt");
    }

    @Test
    @TestMetadata("Provides_Interface_MayNotHaveTypeParameters.kt")
    public void testProvides_Interface_MayNotHaveTypeParameters() {
      runTest("compiler-tests/src/test/data/diagnostic/provides/Provides_Interface_MayNotHaveTypeParameters.kt");
    }
  }
}
