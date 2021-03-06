package com.jetbrains.env.python.testing;

import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.MockPrinter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.PathUtil;
import com.jetbrains.env.EnvTestTagsRequired;
import com.jetbrains.env.PyEnvTestCase;
import com.jetbrains.env.PyProcessWithConsoleTestTask;
import com.jetbrains.env.ut.PyTestTestProcessRunner;
import com.jetbrains.python.sdkTools.SdkCreationType;
import com.jetbrains.python.testing.PythonTestConfigurationsModel;
import com.jetbrains.python.testing.universalTests.PyUniversalPyTestConfiguration;
import com.jetbrains.python.testing.universalTests.PyUniversalPyTestFactory;
import com.jetbrains.python.testing.universalTests.TestTargetType;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * User : catherine
 */
@EnvTestTagsRequired(tags = "pytest")
public final class PythonPyTestingTest extends PyEnvTestCase {

  @Test
  public void testConfigurationProducer() throws Exception {
    runPythonTest(
      new CreateConfigurationTestTask<>(PythonTestConfigurationsModel.PY_TEST_NAME, PyUniversalPyTestConfiguration.class));
  }

  /**
   * Checks tests are resolved when launched from subfolder
   */
  @Test
  public void testTestsInSubFolderResolvable() throws Exception {
    runPythonTest(
      new PyUnitTestProcessWithConsoleTestTask.PyTestsInSubFolderRunner<PyTestTestProcessRunner>("test_metheggs", "test_funeggs", "test_first") {
        @NotNull
        @Override
        protected PyTestTestProcessRunner createProcessRunner() throws Exception {
          return new PyTestTestProcessRunner(toFullPath("tests"), 0) {
            @Override
            protected void configurationCreatedAndWillLaunch(@NotNull PyUniversalPyTestConfiguration configuration) throws IOException {
              super.configurationCreatedAndWillLaunch(configuration);
              configuration.setWorkingDirectory(getWorkingFolderForScript());
            }
          };
        }
      });
  }

  /**
   * Ensures test output works
   */
  @Test
  public void testOutput() throws Exception {
    runPythonTest(
      new PyUnitTestProcessWithConsoleTestTask.PyTestsOutputRunner<PyTestTestProcessRunner>("test_metheggs", "test_funeggs", "test_first") {
        @NotNull
        @Override
        protected PyTestTestProcessRunner createProcessRunner() throws Exception {
          return new PyTestTestProcessRunner(toFullPath("tests"), 0) {
            @Override
            protected void configurationCreatedAndWillLaunch(@NotNull PyUniversalPyTestConfiguration configuration) throws IOException {
              super.configurationCreatedAndWillLaunch(configuration);
              configuration.setWorkingDirectory(getWorkingFolderForScript());
            }
          };
        }
      });
  }

  @Test(expected = RuntimeConfigurationWarning.class)
  public void testValidation() throws Exception {

    final CreateConfigurationTestTask.PyConfigurationCreationTask<PyUniversalPyTestConfiguration> task =
      new CreateConfigurationTestTask.PyConfigurationCreationTask<PyUniversalPyTestConfiguration>() {
        @NotNull
        @Override
        protected PyUniversalPyTestFactory createFactory() {
          return PyUniversalPyTestFactory.INSTANCE;
        }
      };
    runPythonTest(task);
    task.checkEmptyTarget();
  }

  @Test
  public void testConfigurationProducerOnDirectory() throws Exception {
    runPythonTest(
      new CreateConfigurationTestTask.CreateConfigurationTestAndRenameFolderTask(PythonTestConfigurationsModel.PY_TEST_NAME,
                                                                                 PyUniversalPyTestConfiguration.class));
  }

  @Test
  public void testProduceConfigurationOnFile() throws Exception {
    runPythonTest(
      new CreateConfigurationTestTask(PythonTestConfigurationsModel.PY_TEST_NAME, PyUniversalPyTestConfiguration.class, "spam.py") {
        @NotNull
        @Override
        protected PsiElement getElementToRightClickOnByFile(@NotNull final String fileName) {
          return myFixture.configureByFile(fileName);
        }
      });
  }

  @Test
  public void testRenameClass() throws Exception {
    runPythonTest(
      new CreateConfigurationTestTask.CreateConfigurationTestAndRenameClassTask(
        PythonTestConfigurationsModel.PY_TEST_NAME,
        PyUniversalPyTestConfiguration.class));
  }

  /**
   * Ensure dots in test names do not break anything (PY-13833)
   */
  @Test
  public void testEscape() throws Exception {
    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest/", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner("test_escape_me.py", 0);
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        final String resultTree = runner.getFormattedTestTree().trim();
        final String expectedTree = myFixture.configureByFile("test_escape_me.tree.txt").getText().trim();
        Assert.assertEquals("Test result wrong tree", expectedTree, resultTree);
      }
    });
  }

  // Import error should lead to test failure
  @Test
  public void testFailInCaseOfError() {
    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest/failTest", SdkCreationType.EMPTY_SDK) {

      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner(".", 0);
      }


      @Override
      protected void checkTestResults(@NotNull PyTestTestProcessRunner runner,
                                      @NotNull String stdout,
                                      @NotNull String stderr,
                                      @NotNull String all) {
        Assert.assertThat("Import error is not marked as error", runner.getFailedTestsCount(), Matchers.greaterThanOrEqualTo(1));
      }
    });
  }

  /**
   * Ensure project dir is used as curdir even if not set explicitly
   */
  @Test
  public void testCurrentDir() throws Exception {
    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest/", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner("", 0) {
          @Override
          protected void configurationCreatedAndWillLaunch(@NotNull final PyUniversalPyTestConfiguration configuration) throws IOException {
            super.configurationCreatedAndWillLaunch(configuration);
            configuration.setWorkingDirectory(null);
            final VirtualFile fullFilePath = myFixture.getTempDirFixture().getFile("dir_test.py");
            assert fullFilePath != null : String.format("No dir_test.py in %s", myFixture.getTempDirFixture().getTempDirPath());
            configuration.getTarget().setTarget(fullFilePath.getPath());
            configuration.getTarget().setTargetType(TestTargetType.PATH);
          }
        };
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        final String projectDir = myFixture.getProject().getBaseDir().getPath();
        Assert.assertThat("No directory found in output", runner.getConsole().getText(),
                          Matchers.containsString(String.format("Directory %s", PathUtil.toSystemDependentName(projectDir))));
      }
    });
  }

  @Test
  public void testPytestRunner() {

    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner("test1.py", 0);
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        assertEquals(3, runner.getAllTestsCount());
        assertEquals(3, runner.getPassedTestsCount());
        runner.assertAllTestsPassed();


        // This test has "sleep(1)", so duration should be >=1000
        final AbstractTestProxy testForOneSecond = runner.findTestByName("testOne");
        Assert.assertThat("Wrong duration", testForOneSecond.getDuration(), Matchers.greaterThanOrEqualTo(1000L));
      }
    });
  }

  /**
   * Ensure we can run path like "spam.bar" where "spam" is folder with out of init.py
   */
  @Test
  public void testPyTestFolderNoInitPy() {
    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        if (getLevelForSdk().isPy3K()) {
          return new PyTestTestProcessRunner("folder_no_init_py/test_test.py", 2);
        }
         else {
          return new PyTestTestProcessRunner(toFullPath("folder_no_init_py/test_test.py"), 2) {
            @Override
            protected void configurationCreatedAndWillLaunch(@NotNull PyUniversalPyTestConfiguration configuration) throws IOException {
              super.configurationCreatedAndWillLaunch(configuration);
              configuration.setWorkingDirectory(getWorkingFolderForScript());
            }
          };
        }
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        assertEquals(runner.getFormattedTestTree(), 1, runner.getFailedTestsCount());
        if (runner.getCurrentRerunStep() == 0) {
          assertEquals(runner.getFormattedTestTree(), 2, runner.getAllTestsCount());
          assertEquals(runner.getFormattedTestTree(), 1, runner.getPassedTestsCount());
        }
        else {
          assertEquals(runner.getFormattedTestTree(), 1, runner.getAllTestsCount());
          assertEquals(runner.getFormattedTestTree(), 0, runner.getPassedTestsCount());
        }
      }
    });
  }

  @Test
  public void testPytestRunner2() {
    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/pytest", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner("test2.py", 1);
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        if (runner.getCurrentRerunStep() > 0) {
          // We rerun all tests, since running parametrized tests is broken until
          // https://github.com/JetBrains/teamcity-messages/issues/121
          assertEquals(runner.getFormattedTestTree(), 7, runner.getAllTestsCount());
          assertEquals(runner.getFormattedTestTree(), 3, runner.getPassedTestsCount());
          assertEquals(runner.getFormattedTestTree(), 4, runner.getFailedTestsCount());
          return;
        }
        assertEquals(runner.getFormattedTestTree(), 9, runner.getAllTestsCount());
        assertEquals(runner.getFormattedTestTree(), 5, runner.getPassedTestsCount());
        assertEquals(runner.getFormattedTestTree(), 4, runner.getFailedTestsCount());
        // Py.test may report F before failed test, so we check string contains, not starts with
        Assert
          .assertThat("No test stdout", MockPrinter.fillPrinter(runner.findTestByName("testOne")).getStdOut(),
                      Matchers.containsString("I am test1"));

        // Ensure test has stdout even it fails
        final AbstractTestProxy testFail = runner.findTestByName("testFail");
        Assert.assertThat("No stdout for fail", MockPrinter.fillPrinter(testFail).getStdOut(),
                          Matchers.containsString("I will fail"));

        // This test has "sleep(1)", so duration should be >=1000
        Assert.assertThat("Wrong duration", testFail.getDuration(), Matchers.greaterThanOrEqualTo(1000L));
      }
    });
  }


  /**
   * Ensures file references are highlighted for pytest traceback
   */
  @Test
  public void testPyTestFileReferences() {
    final String fileName = "reference_tests.py";

    runPythonTest(new PyProcessWithConsoleTestTask<PyTestTestProcessRunner>("/testRunner/env/unit", SdkCreationType.EMPTY_SDK) {
      @NotNull
      @Override
      protected PyTestTestProcessRunner createProcessRunner() throws Exception {
        return new PyTestTestProcessRunner(fileName, 0);
      }

      @Override
      protected void checkTestResults(@NotNull final PyTestTestProcessRunner runner,
                                      @NotNull final String stdout,
                                      @NotNull final String stderr,
                                      @NotNull final String all) {
        final List<String> fileNames = runner.getHighlightedStringsInConsole().second;
        Assert.assertThat("No lines highlighted", fileNames, Matchers.not(Matchers.empty()));
        // PyTest highlights file:line_number
        Assert.assertTrue("Assert fail not marked", fileNames.contains("reference_tests.py:7"));
        Assert.assertTrue("Failed test not marked", fileNames.contains("reference_tests.py:12"));
        Assert.assertTrue("Failed test not marked", fileNames.contains("reference_tests.py"));
      }
    });
  }
}
