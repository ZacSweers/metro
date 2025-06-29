

package dev.zacsweers.metro.compiler;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link dev.zacsweers.metro.compiler.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler-tests/src/test/data/diagnostic/ir")
@TestDataPath("$PROJECT_ROOT")
public class IrDiagnosticTestGenerated extends AbstractIrDiagnosticTest {
  @Test
  public void testAllFilesPresentInIr() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-tests/src/test/data/diagnostic/ir"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
  }

  @Nested
  @TestMetadata("compiler-tests/src/test/data/diagnostic/ir/cycles")
  @TestDataPath("$PROJECT_ROOT")
  public class Cycles {
    @Test
    public void testAllFilesPresentInCycles() {
      KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-tests/src/test/data/diagnostic/ir/cycles"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("CyclesShouldFailAcrossMultipleGraphs.kt")
    public void testCyclesShouldFailAcrossMultipleGraphs() {
      runTest("compiler-tests/src/test/data/diagnostic/ir/cycles/CyclesShouldFailAcrossMultipleGraphs.kt");
    }
  }
}
