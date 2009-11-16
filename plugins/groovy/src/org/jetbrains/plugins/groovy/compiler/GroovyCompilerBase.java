/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package org.jetbrains.plugins.groovy.compiler;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.compiler.impl.FileSetCompileScope;
import com.intellij.compiler.impl.javaCompiler.ModuleChunk;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.compiler.TranslatingCompiler;
import com.intellij.openapi.compiler.ex.CompileContextEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.ModuleFileIndex;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.Chunk;
import com.intellij.util.PathUtil;
import com.intellij.util.PathsList;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.groovy.compiler.rt.CompilerMessage;
import org.jetbrains.groovy.compiler.rt.GroovycRunner;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.config.GroovyConfigUtils;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * @author peter
 */
public abstract class GroovyCompilerBase implements TranslatingCompiler {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.plugins.groovy.compiler.GroovyCompilerBase");
  protected final Project myProject;

  public GroovyCompilerBase(Project project) {
    myProject = project;
  }

  protected void runGroovycCompiler(CompileContext compileContext, final Module module,
                                    final List<VirtualFile> toCompile,
                                    boolean forStubs,
                                    VirtualFile outputDir,
                                    OutputSink sink, boolean tests) {
    GeneralCommandLine commandLine = new GeneralCommandLine();
    final Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    assert sdk != null; //verified before
    SdkType sdkType = sdk.getSdkType();
    assert sdkType instanceof JavaSdkType;
    commandLine.setExePath(((JavaSdkType)sdkType).getVMExecutablePath(sdk));

    final PathsList classPathBuilder = new PathsList();
    classPathBuilder.add(PathUtil.getJarPathForClass(GroovycRunner.class));

    final ModuleChunk chunk = createChunk(module, compileContext);

    final Library[] libraries = GroovyConfigUtils.getInstance().getSDKLibrariesByModule(module);
    if (libraries.length > 0) {
      classPathBuilder.addVirtualFiles(Arrays.asList(libraries[0].getFiles(OrderRootType.COMPILATION_CLASSES)));
    }

    classPathBuilder.addVirtualFiles(chunk.getCompilationClasspathFiles());
    appendOutputPath(module, classPathBuilder, false);
    if (tests) {
      appendOutputPath(module, classPathBuilder, true);
    }

    final List<String> patchers = new SmartList<String>();
    for (final GroovyCompilerExtension extension : GroovyCompilerExtension.EP_NAME.getExtensions()) {
      extension.enhanceCompilationClassPath(chunk, classPathBuilder);
      patchers.addAll(extension.getCompilationUnitPatchers(chunk));
    }

    if ("true".equals(System.getProperty("profile.groovy.compiler"))) {
      commandLine.addParameter("-Djava.library.path=" + PathManager.getBinPath());
      commandLine.addParameter("-Dprofile.groovy.compiler=true");
      commandLine.addParameter("-agentlib:yjpagent=disablej2ee,disablecounts,disablealloc,sessionname=GroovyCompiler");
      classPathBuilder.add(PathManager.findFileInLibDirectory("yjp-controller-api-redist.jar").getAbsolutePath());
    }

    commandLine.addParameter("-cp");
    commandLine.addParameter(classPathBuilder.getPathsString());


    commandLine.addParameter("-Xmx" + GroovyCompilerConfiguration.getInstance(myProject).getHeapSize() + "m");
    commandLine.addParameter("-XX:+HeapDumpOnOutOfMemoryError");

    //debug
    //commandLine.addParameter("-Xdebug"); commandLine.addParameter("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5239");

    // Setting up process encoding according to locale
    final ArrayList<String> list = new ArrayList<String>();
    CompilerUtil.addLocaleOptions(list, false);
    commandLine.addParameters(list);

    commandLine.addParameter(GroovycRunner.class.getName());

    try {
      File fileWithParameters = File.createTempFile("toCompile", "");
      fillFileWithGroovycParameters(toCompile, fileWithParameters, outputDir, patchers, getMainOutput(compileContext, module, tests));

      commandLine.addParameter(forStubs ? "stubs" : "groovyc");
      commandLine.addParameter(fileWithParameters.getPath());
    }
    catch (IOException e) {
      LOG.error(e);
    }

    GroovycOSProcessHandler processHandler;

    try {
      processHandler = new GroovycOSProcessHandler(compileContext, commandLine.createProcess(), commandLine.getCommandLineString(), sink);

      processHandler.startNotify();
      processHandler.waitFor();

      final List<VirtualFile> toRecompile = new ArrayList<VirtualFile>();
      Set<File> toRecompileFiles = processHandler.getToRecompileFiles();
      for (File toRecompileFile : toRecompileFiles) {
        final VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(toRecompileFile);
        LOG.assertTrue(vFile != null);
        toRecompile.add(vFile);
      }

      final List<CompilerMessage> messages = processHandler.getCompilerMessages();
      for (CompilerMessage compilerMessage : messages) {
        final CompilerMessageCategory category;
        category = getMessageCategory(compilerMessage);

        final String url = compilerMessage.getUrl();

        compileContext.addMessage(category, compilerMessage.getMessage(), VfsUtil.pathToUrl(FileUtil.toSystemIndependentName(url)), compilerMessage.getLineNum(),
                                  compilerMessage.getColumnNum());
      }

      boolean hasMessages = !messages.isEmpty();

      StringBuffer unparsedBuffer = processHandler.getUnparsedOutput();
      if (unparsedBuffer.length() != 0) {
        compileContext.addMessage(CompilerMessageCategory.ERROR, unparsedBuffer.toString(), null, -1, -1);
        hasMessages = true;
      }

      final int exitCode = processHandler.getProcess().exitValue();
      if (!hasMessages && exitCode != 0) {
        compileContext.addMessage(CompilerMessageCategory.ERROR, "Internal groovyc error: code " + exitCode, null, -1, -1);
      }

      List<OutputItem> outputItems = processHandler.getSuccessfullyCompiled();
      if (forStubs) {
        List<VirtualFile> stubFiles = new ArrayList<VirtualFile>();
        for (final OutputItem outputItem : outputItems) {
          final File stub = new File(outputItem.getOutputPath());
          CompilerUtil.refreshIOFile(stub);
          final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(stub);
          ContainerUtil.addIfNotNull(file, stubFiles);
        }
        ((CompileContextEx)compileContext).addScope(new FileSetCompileScope(stubFiles, new Module[]{module}));
        outputItems = Collections.emptyList();
      }

      sink.add(outputDir.getPath(), outputItems, VfsUtil.toVirtualFileArray(toRecompile));
    }
    catch (ExecutionException e) {
      LOG.error(e);
    }
  }

  protected static VirtualFile getMainOutput(CompileContext compileContext, Module module, boolean tests) {
    return tests ? compileContext.getModuleOutputDirectoryForTests(module) : compileContext.getModuleOutputDirectory(module);
  }

  private static CompilerMessageCategory getMessageCategory(CompilerMessage compilerMessage) {
    String category;
    category = compilerMessage.getCategory();

    if (CompilerMessage.ERROR.equals(category)) return CompilerMessageCategory.ERROR;
    if (CompilerMessage.INFORMATION.equals(category)) return CompilerMessageCategory.INFORMATION;
    if (CompilerMessage.STATISTICS.equals(category)) return CompilerMessageCategory.STATISTICS;
    if (CompilerMessage.WARNING.equals(category)) return CompilerMessageCategory.WARNING;

    return CompilerMessageCategory.ERROR;
  }

  private void fillFileWithGroovycParameters(List<VirtualFile> virtualFiles, File f, VirtualFile outputDir, final List<String> patchers, VirtualFile finalOutputDir) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Running groovyc on: " + virtualFiles.toString());
    }

    FileOutputStream stream;
    try {
      stream = new FileOutputStream(f);
    }
    catch (FileNotFoundException e) {
      LOG.error(e);
      return;
    }

    final PrintStream printer = new PrintStream(stream);

    for (final VirtualFile item : virtualFiles) {
      printer.println(GroovycRunner.SRC_FILE);
      printer.println(item.getPath());
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          final PsiFile file = PsiManager.getInstance(myProject).findFile(item);
          if (file instanceof GroovyFileBase) {
            for (PsiClass psiClass : ((GroovyFileBase)file).getClasses()) {
              printer.println(psiClass.getQualifiedName());
            }
          }
        }
      });
      printer.println(GroovycRunner.END);
    }

    if (!patchers.isEmpty()) {
      printer.println(GroovycRunner.PATCHERS);
      for (final String patcher : patchers) {
        printer.println(patcher);
      }
      printer.println(GroovycRunner.END);
    }

    final Charset ideCharset = EncodingProjectManager.getInstance(myProject).getDefaultCharset();
    if (!Comparing.equal(CharsetToolkit.getDefaultSystemCharset(), ideCharset)) {
      printer.println(GroovycRunner.ENCODING);
      printer.println(ideCharset.name());
    }

    printer.println(GroovycRunner.OUTPUTPATH);
    printer.println(PathUtil.getLocalPath(outputDir));

    printer.println(GroovycRunner.FINAL_OUTPUTPATH);
    printer.println(finalOutputDir.getPath());


    printer.close();
  }

  private static void appendOutputPath(Module module, PathsList compileClasspath, final boolean forTestClasses) {
    String output = CompilerPaths.getModuleOutputPath(module, forTestClasses);
    if (output != null) {
      compileClasspath.add(FileUtil.toSystemDependentName(output));
    }
  }

  private static ModuleChunk createChunk(Module module, CompileContext context) {
    return new ModuleChunk((CompileContextEx)context, new Chunk<Module>(module), Collections.<Module, List<VirtualFile>>emptyMap());
  }

  public void compile(final CompileContext compileContext, Chunk<Module> moduleChunk, final VirtualFile[] virtualFiles, OutputSink sink) {
    Map<Module, List<VirtualFile>> mapModulesToVirtualFiles;
    if (moduleChunk.getNodes().size() == 1) {
      mapModulesToVirtualFiles = Collections.singletonMap(moduleChunk.getNodes().iterator().next(), Arrays.asList(virtualFiles));
    }
    else {
      mapModulesToVirtualFiles = CompilerUtil.buildModuleToFilesMap(compileContext, virtualFiles);
    }
    for (final Module module : moduleChunk.getNodes()) {
      final List<VirtualFile> moduleFiles = mapModulesToVirtualFiles.get(module);
      if (moduleFiles == null) {
        continue;
      }

      final ModuleFileIndex index = ModuleRootManager.getInstance(module).getFileIndex();
      final List<VirtualFile> toCompile = new ArrayList<VirtualFile>();
      final List<VirtualFile> toCompileTests = new ArrayList<VirtualFile>();
      final CompilerConfiguration configuration = CompilerConfiguration.getInstance(myProject);

      if (module.getModuleType() instanceof JavaModuleType) {
        for (final VirtualFile file : moduleFiles) {
          final boolean shouldCompile = !configuration.isResourceFile(file) &&
                                        (file.getFileType() == GroovyFileType.GROOVY_FILE_TYPE ||
                                         file.getFileType() == StdFileTypes.JAVA);
          if (shouldCompile) {
            (index.isInTestSourceContent(file) ? toCompileTests : toCompile).add(file);
          }
        }
      }

      if (!toCompile.isEmpty()) {
        compileFiles(compileContext, module, toCompile, sink, false);
      }
      if (!toCompileTests.isEmpty()) {
        compileFiles(compileContext, module, toCompileTests, sink, true);
      }

    }

  }

  protected abstract void compileFiles(CompileContext compileContext, Module module,
                                       List<VirtualFile> toCompile, OutputSink sink, boolean tests);

  public boolean isCompilableFile(VirtualFile file, CompileContext context) {
    final boolean result = GroovyFileType.GROOVY_FILE_TYPE.equals(file.getFileType());
    if (result && LOG.isDebugEnabled()) {
      LOG.debug("compilable file: " + file.getPath());
    }
    return result;
  }
}
