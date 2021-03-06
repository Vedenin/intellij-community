/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.intellij.build.impl

import com.intellij.openapi.util.io.FileUtil
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.codehaus.gant.GantBinding
import org.jetbrains.intellij.build.BuildMessages
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths
import org.jetbrains.intellij.build.CompilationContext
import org.jetbrains.jps.gant.JpsGantProjectBuilder
import org.jetbrains.jps.model.JpsGlobal
import org.jetbrains.jps.model.JpsProject
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.serialization.JpsModelSerializationDataService
import org.jetbrains.jps.model.serialization.JpsProjectLoader
import org.jetbrains.jps.util.JpsPathUtil

import java.util.function.BiFunction
/**
 * @author nik
 */
@CompileStatic
class CompilationContextImpl implements CompilationContext {
  final AntBuilder ant
  final BuildOptions options
  final BuildMessages messages
  final BuildPaths paths
  final JpsProject project
  final JpsGlobal global
  final JpsGantProjectBuilder projectBuilder

  @CompileDynamic
  static CompilationContextImpl create(String communityHome, String projectHome, String defaultOutputRoot, Script gantScript) {
    GantBinding binding = (GantBinding) gantScript.binding
    //noinspection GrUnresolvedAccess, GroovyAssignabilityCheck
    return create(binding.ant, binding.projectBuilder, binding.project, binding.global, communityHome, projectHome,
                   { p, m -> defaultOutputRoot } as BiFunction<JpsProject, BuildMessages, String>, new BuildOptions())
   }

  static CompilationContextImpl create(AntBuilder ant, JpsGantProjectBuilder projectBuilder, JpsProject project, JpsGlobal global,
                                       String communityHome, String projectHome,
                                       BiFunction<JpsProject, BuildMessages, String> buildOutputRootEvaluator, BuildOptions options) {
    def messages = BuildMessagesImpl.create(projectBuilder, ant.project)
    communityHome = toCanonicalPath(communityHome)
    if (["platform/build-scripts", "bin/log.xml", "build.txt"].any { !new File(communityHome, it).exists() }) {
      messages.error("communityHome ($communityHome) doesn't point to a directory containing IntelliJ Community sources")
    }

    projectHome = toCanonicalPath(projectHome)
    def jdk8Home = toCanonicalPath(JdkUtils.computeJdkHome(messages, "jdk8Home", "$projectHome/build/jdk/1.8", "JDK_18_x64"))
    if (project.modules.isEmpty()) {
      loadProject(communityHome, projectHome, jdk8Home, project, global, messages)
    }
    else {
      //todo[nik] currently we need this to build IDEA CE from IDEA UI build scripts. It would be better to create a separate JpsProject instance instead
      messages.info("Skipping loading project because it's already loaded")
    }

    def context = new CompilationContextImpl(ant, projectBuilder, project, global, communityHome, projectHome, jdk8Home, messages,
                                             buildOutputRootEvaluator, options)
    context.prepareForBuild()
    return context
  }

  private CompilationContextImpl(AntBuilder ant, JpsGantProjectBuilder projectBuilder, JpsProject project, JpsGlobal global,
                                 String communityHome, String projectHome, String jdk8Home, BuildMessages messages,
                                 BiFunction<JpsProject, BuildMessages, String> buildOutputRootEvaluator, BuildOptions options) {
    this.ant = ant
    this.project = project
    this.global = global
    this.options = options
    this.projectBuilder = projectBuilder
    this.messages = messages
    String buildOutputRoot = options.outputRootPath ?: buildOutputRootEvaluator.apply(project, messages)
    this.paths = new BuildPathsImpl(communityHome, projectHome, buildOutputRoot, jdk8Home)
  }

  CompilationContextImpl createCopy(AntBuilder ant, BuildMessages messages, BuildOptions options,
                                    BiFunction<JpsProject, BuildMessages, String> buildOutputRootEvaluator) {
    return new CompilationContextImpl(ant, projectBuilder, project, global, paths.communityHome, paths.projectHome, paths.jdkHome,
                                      messages, buildOutputRootEvaluator, options)
  }

  private static void loadProject(String communityHome, String projectHome, String jdkHome, JpsProject project, JpsGlobal global,
                                  BuildMessages messages) {
    def bundledKotlinPath = "$communityHome/build/kotlinc"
    if (!new File(bundledKotlinPath, "lib/kotlin-runtime.jar").exists()) {
      messages.error(
        "Could not find Kotlin runtime at $bundledKotlinPath/lib/kotlin-runtime.jar: run download_kotlin.gant script to download Kotlin JARs")
    }
    JpsModelSerializationDataService.getOrCreatePathVariablesConfiguration(global).addPathVariable("KOTLIN_BUNDLED", bundledKotlinPath)

    JdkUtils.defineJdk(global, "IDEA jdk", JdkUtils.computeJdkHome(messages, "jdkHome", "$projectHome/build/jdk/1.6", "JDK_16_x64"))
    JdkUtils.defineJdk(global, "1.8", jdkHome)

    def pathVariables = JpsModelSerializationDataService.computeAllPathVariables(global)
    JpsProjectLoader.loadProject(project, pathVariables, projectHome)
    messages.info("Loaded project $projectHome: ${project.modules.size()} modules, ${project.libraryCollection.libraries.size()} libraries")
  }

  void prepareForBuild() {
    checkCompilationOptions()
    projectBuilder.buildIncrementally = options.incrementalCompilation
    def dataDirName = options.incrementalCompilation ? ".jps-build-data-incremental" : ".jps-build-data"
    projectBuilder.dataStorageRoot = new File(paths.buildOutputRoot, dataDirName)
    def logDir = new File(paths.buildOutputRoot, "log")
    FileUtil.delete(logDir)
    projectBuilder.setupAdditionalLogging(new File("$logDir/compilation.log"), System.getProperty("intellij.build.debug.logging.categories", ""))

    def classesDirName = "classes"
    def classesOutput = "$paths.buildOutputRoot/$classesDirName"
    List<String> outputDirectoriesToKeep = ["log"]
    if (options.pathToCompiledClassesArchive != null) {
      unpackCompiledClasses(messages, ant, classesOutput, options)
      outputDirectoriesToKeep.add(classesDirName)
    }
    if (options.incrementalCompilation) {
      outputDirectoriesToKeep.add(dataDirName)
      outputDirectoriesToKeep.add(classesDirName)
    }
    if (!options.useCompiledClassesFromProjectOutput) {
      projectBuilder.targetFolder = classesOutput
    }
    else {
      def outputDir = getProjectOutputDirectory()
      if (!outputDir.exists()) {
        messages.error("$BuildOptions.USE_COMPILED_CLASSES_PROPERTY is enabled, but the project output directory $outputDir.absolutePath doesn't exist")
      }
    }

    suppressWarnings(project)
    projectBuilder.exportModuleOutputProperties()
    cleanOutput(outputDirectoriesToKeep)
  }

  File getProjectOutputDirectory() {
    JpsPathUtil.urlToFile(JpsJavaExtensionService.getInstance().getOrCreateProjectExtension(project).outputUrl)
  }

  void cleanOutput(List<String> outputDirectoriesToKeep) {
    messages.block("Clean output") {
      def outputPath = paths.buildOutputRoot
      messages.progress("Cleaning output directory $outputPath")
      new File(outputPath).listFiles()?.each { File file ->
        if (outputDirectoriesToKeep.contains(file.name)) {
          messages.info("Skipped cleaning for $file.absolutePath")
        }
        else {
          FileUtil.delete(file)
        }
      }
    }
  }


  @CompileDynamic
  private static void unpackCompiledClasses(BuildMessages messages, AntBuilder ant, String classesOutput, BuildOptions options) {
    messages.block("Unpack compiled classes archive") {
      FileUtil.delete(new File(classesOutput))
      ant.unzip(src: options.pathToCompiledClassesArchive, dest: classesOutput)
    }
  }

  private void checkCompilationOptions() {
    if (options.useCompiledClassesFromProjectOutput && options.incrementalCompilation) {
      messages.warning("'${BuildOptions.USE_COMPILED_CLASSES_PROPERTY}' is specified, so 'incremental compilation' option will be ignored")
      options.incrementalCompilation = false
    }
    if (options.pathToCompiledClassesArchive != null && options.incrementalCompilation) {
      messages.warning("Paths to the compiled project output is specified, so 'incremental compilation' option will be ignored")
      options.incrementalCompilation = false
    }
    if (options.pathToCompiledClassesArchive != null && options.useCompiledClassesFromProjectOutput) {
      messages.warning("'${BuildOptions.USE_COMPILED_CLASSES_PROPERTY}' is specified, so the archive with compiled project output won't be used")
      options.pathToCompiledClassesArchive = null
    }
  }

  private static void suppressWarnings(JpsProject project) {
    def compilerOptions = JpsJavaExtensionService.instance.getOrCreateCompilerConfiguration(project).currentCompilerOptions
    compilerOptions.GENERATE_NO_WARNINGS = true
    compilerOptions.DEPRECATION = false
    compilerOptions.ADDITIONAL_OPTIONS_STRING = compilerOptions.ADDITIONAL_OPTIONS_STRING.replace("-Xlint:unchecked", "")
  }

  @Override
  JpsModule findRequiredModule(String name) {
    def module = findModule(name)
    if (module == null) {
      messages.error("Cannot find required module '$name' in the project")
    }
    return module
  }

  JpsModule findModule(String name) {
    project.modules.find { it.name == name }
  }

  private static String toCanonicalPath(String communityHome) {
    FileUtil.toSystemIndependentName(new File(communityHome).canonicalPath)
  }
}

class BuildPathsImpl extends BuildPaths {
  BuildPathsImpl(String communityHome, String projectHome, String buildOutputRoot, String jdkHome) {
    this.communityHome = communityHome
    this.projectHome = projectHome
    this.buildOutputRoot = buildOutputRoot
    this.jdkHome = jdkHome
    artifacts = "$buildOutputRoot/artifacts"
    distAll = "$buildOutputRoot/dist.all"
    temp = "$buildOutputRoot/temp"
  }
}
