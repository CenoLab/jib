/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import com.google.cloud.tools.jib.filesystem.DirectoryWalker;
import com.google.cloud.tools.jib.frontend.JavaLayerConfigurations;
import com.google.cloud.tools.jib.plugins.common.ZipUtil;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.WarPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.War;

/** Builds {@link JavaLayerConfigurations} based on inputs from a {@link Project}. */
class GradleLayerConfigurations {

  /** Name of the `main` {@link SourceSet} to use as source files. */
  private static final String MAIN_SOURCE_SET_NAME = "main";

  static JavaLayerConfigurations getForProject(
      Project project, GradleJibLogger gradleJibLogger, Path extraDirectory) throws IOException {
    War war = GradleProjectProperties.getWar(project);
    if (war != null) {

      // BEGIN DEBUGGING CODE
      Convention convention = project.getConvention();
      WarPluginConvention warPluginConvention = convention.getPlugin(WarPluginConvention.class);

      File webAppDir = warPluginConvention.getWebAppDir();

      gradleJibLogger.warn("### Web App Dir ###");
      new DirectoryWalker(webAppDir.toPath()).walk(path -> gradleJibLogger.warn(path.toString()));

      gradleJibLogger.warn("### WAR classpath ###");
      FileCollection classpath = war.getClasspath();
      for (File f : classpath) {
        gradleJibLogger.warn('\t' + f.toString());
      }

      gradleJibLogger.warn("### Other details ###");
      gradleJibLogger.warn(war.getDescription());
      gradleJibLogger.warn(war.getArchiveName());
      Path archivePath = war.getArchivePath().toPath();
      gradleJibLogger.warn(archivePath.toString());
      // END DEBUGGING CODE

      gradleJibLogger.info("War project identified: " + project.getDisplayName());
      return getForWar(war, gradleJibLogger, extraDirectory);
    } else {
      return getForJarProject(project, gradleJibLogger, extraDirectory);
    }
  }

  private static JavaLayerConfigurations getForWar(
      War war, GradleJibLogger gradleJibLogger, Path extraDirectory) throws IOException {
    Path archivePath = war.getArchivePath().toPath();
    Path explodedWar = Files.createTempDirectory("jib-exploded-war");

    gradleJibLogger.info("Unpacking WAR " + archivePath + " into " + explodedWar);
    ZipUtil.unzip(archivePath, explodedWar);

    List<Path> warFiles = new ArrayList<>();
    try (Stream<Path> fileStream = Files.list(explodedWar)) {
      fileStream.forEach(path -> gradleJibLogger.debug("  " + path));
      fileStream.forEach(warFiles::add);
    }

    // Sorts all files by path for consistent ordering.
    Collections.sort(warFiles);

    // BEGIN DEBUGGING CODE
    explodedWar.toFile().deleteOnExit();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        MoreFiles.deleteRecursively(explodedWar, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException ex) {} // ignore
    }));

    gradleJibLogger.warn("### warFiles ###");
    for (Path p : warFiles) {
      gradleJibLogger.warn(p.toString());
    }
    // END DEBUGGING CODE

    return JavaLayerConfigurations.builder().setExplodedWar(warFiles).build();
  }

  /**
   * Resolves the source files configuration for a Gradle {@link Project}.
   *
   * @param project the Gradle {@link Project}
   * @param gradleJibLogger the build logger for providing feedback about the resolution
   * @param extraDirectory path to the directory for the extra files layer
   * @return a {@link JavaLayerConfigurations} for the layers for the Gradle {@link Project}
   * @throws IOException if an I/O exception occurred during resolution
   */
  private static JavaLayerConfigurations getForJarProject(
      Project project, GradleJibLogger gradleJibLogger, Path extraDirectory) throws IOException {
    JavaPluginConvention javaPluginConvention =
        project.getConvention().getPlugin(JavaPluginConvention.class);

    SourceSet mainSourceSet = javaPluginConvention.getSourceSets().getByName(MAIN_SOURCE_SET_NAME);

    List<Path> dependenciesFiles = new ArrayList<>();
    List<Path> snapshotDependenciesFiles = new ArrayList<>();
    List<Path> resourcesFiles = new ArrayList<>();
    List<Path> classesFiles = new ArrayList<>();
    List<Path> extraFiles = new ArrayList<>();

    // Adds each file in each classes output directory to the classes files list.
    FileCollection classesOutputDirectories = mainSourceSet.getOutput().getClassesDirs();
    gradleJibLogger.info("Adding corresponding output directories of source sets to image");
    for (File classesOutputDirectory : classesOutputDirectories) {
      if (Files.notExists(classesOutputDirectory.toPath())) {
        gradleJibLogger.info("\t'" + classesOutputDirectory + "' (not found, skipped)");
        continue;
      }
      gradleJibLogger.info("\t'" + classesOutputDirectory + "'");
      try (Stream<Path> classFileStream = Files.list(classesOutputDirectory.toPath())) {
        classFileStream.forEach(classesFiles::add);
      }
    }
    if (classesFiles.isEmpty()) {
      gradleJibLogger.warn("No classes files were found - did you compile your project?");
    }

    // Adds each file in the resources output directory to the resources files list.
    Path resourcesOutputDirectory = mainSourceSet.getOutput().getResourcesDir().toPath();
    if (Files.exists(resourcesOutputDirectory)) {
      try (Stream<Path> resourceFileStream = Files.list(resourcesOutputDirectory)) {
        resourceFileStream.forEach(resourcesFiles::add);
      }
    }

    // Adds all other files to the dependencies files list.
    FileCollection allFiles = mainSourceSet.getRuntimeClasspath();
    // Removes the classes output directories.
    allFiles = allFiles.minus(classesOutputDirectories);
    for (File dependencyFile : allFiles) {
      // Removes the resources output directory.
      if (resourcesOutputDirectory.equals(dependencyFile.toPath())) {
        continue;
      }
      if (dependencyFile.getName().contains("SNAPSHOT")) {
        snapshotDependenciesFiles.add(dependencyFile.toPath());
      } else {
        dependenciesFiles.add(dependencyFile.toPath());
      }
    }

    // Adds all the extra files.
    if (Files.exists(extraDirectory)) {
      try (Stream<Path> extraFilesLayerDirectoryFiles = Files.list(extraDirectory)) {
        extraFiles = extraFilesLayerDirectoryFiles.collect(Collectors.toList());
      }
    }

    // Sorts all files by path for consistent ordering.
    Collections.sort(dependenciesFiles);
    Collections.sort(snapshotDependenciesFiles);
    Collections.sort(resourcesFiles);
    Collections.sort(classesFiles);
    Collections.sort(extraFiles);

    return JavaLayerConfigurations.builder()
        .setDependenciesFiles(dependenciesFiles)
        .setSnapshotDependenciesFiles(snapshotDependenciesFiles)
        .setResourcesFiles(resourcesFiles)
        .setClassesFiles(classesFiles)
        .setExtraFiles(extraFiles)
        .build();
  }

  private GradleLayerConfigurations() {}
}
