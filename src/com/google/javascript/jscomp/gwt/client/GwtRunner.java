/*
 * Copyright 2016 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.gwt.client;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.javascript.jscomp.BasicErrorManager;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CommandLineRunnerUtils;
import com.google.javascript.jscomp.CommandLineRunnerUtils.JsModuleSpec;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CompilerOptions.IsolationMode;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.CompilerOptions.TracerMode;
import com.google.javascript.jscomp.DefaultExterns;
import com.google.javascript.jscomp.DependencyOptions;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.JSModule;
import com.google.javascript.jscomp.ModuleIdentifier;
import com.google.javascript.jscomp.PropertyRenamingPolicy;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.SourceMap;
import com.google.javascript.jscomp.SourceMapInput;
import com.google.javascript.jscomp.VariableRenamingPolicy;
import com.google.javascript.jscomp.WarningLevel;
import com.google.javascript.jscomp.deps.ModuleLoader.ResolutionMode;
import com.google.javascript.jscomp.deps.SourceCodeEscapers;
import com.google.javascript.jscomp.resources.ResourceLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

/**
 * Runner for the GWT-compiled JSCompiler.
 */
public final class GwtRunner {
  private static final CompilationLevel DEFAULT_COMPILATION_LEVEL =
      CompilationLevel.SIMPLE_OPTIMIZATIONS;

  private static final String OUTPUT_MARKER = "%output%";
  private static final String OUTPUT_MARKER_JS_STRING = "%output|jsstring%";

  private static final String EXTERNS_PREFIX = "externs/";

  @JsType(namespace = JsPackage.GLOBAL, name = "Object", isNative = true)
  private static class Flags {
    boolean angularPass;
    boolean applyInputSourceMaps;
    boolean assumeFunctionWrapper;
    String compilationLevel;
    boolean dartPass;
    JsMap defines;
    String dependencyMode;
    String[] entryPoint;
    String env;
    boolean exportLocalPropertyDefinitions;
    String[] extraAnnotationNames;
    boolean generateExports;
    String languageIn;
    String languageOut;
    boolean checksOnly;
    boolean newTypeInf;
    String isolationMode;
    String outputWrapper;
    @Deprecated
    boolean polymerPass;
    Double polymerVersion;  // nb. nullable JS number represented by java.lang.Double in GWT.
    boolean preserveTypeAnnotations;
    boolean processClosurePrimitives;
    boolean processCommonJsModules;
    boolean renaming;
    public String renamePrefixNamespace;
    boolean rewritePolyfills;
    String warningLevel;
    boolean useTypesForOptimization;
    String tracerMode;
    String moduleResolutionMode;
    String jsOutputFile;
    String[] formatting;
    String errorFormat;
    boolean sourceMapIncludeContent;
    boolean parseInlineSourceMaps;
    String[] chunk;
    String[] chunkWrapper;
    String chunkOutputPathPrefix;

    // These flags do not match the Java compiler JAR.
    @Deprecated
    File[] jsCode;
    File[] externs;
    boolean createSourceMap;
  }

  /**
   * defaultFlags must have a value set for each field. Otherwise, GWT has no way to create the
   * fields inside Flags (as it's native). If Flags is not-native, GWT eats its field names
   * anyway.
   */
  private static Flags defaultFlags;

  /**
   * Lazy initialize due to GWT. If things are exported then Object is not available when the static
   * initialization runs.
   */
  private static Flags getDefaultFlags() {
    if (defaultFlags != null) {
      return defaultFlags;
    }
    defaultFlags = new Flags();
    defaultFlags.angularPass = false;
    defaultFlags.applyInputSourceMaps = true;
    defaultFlags.assumeFunctionWrapper = false;
    defaultFlags.checksOnly = false;
    defaultFlags.compilationLevel = "SIMPLE";
    defaultFlags.dartPass = false;
    defaultFlags.defines = null;
    defaultFlags.dependencyMode = null;
    defaultFlags.entryPoint = null;
    defaultFlags.env = "BROWSER";
    defaultFlags.exportLocalPropertyDefinitions = false;
    defaultFlags.extraAnnotationNames = null;
    defaultFlags.generateExports = false;
    defaultFlags.languageIn = "ECMASCRIPT_2017";
    defaultFlags.languageOut = "ECMASCRIPT5";
    defaultFlags.newTypeInf = false;
    defaultFlags.isolationMode = "NONE";
    defaultFlags.outputWrapper = null;
    defaultFlags.polymerPass = false;
    defaultFlags.polymerVersion = null;
    defaultFlags.preserveTypeAnnotations = false;
    defaultFlags.processClosurePrimitives = true;
    defaultFlags.processCommonJsModules = false;
    defaultFlags.renamePrefixNamespace = null;
    defaultFlags.renaming = true;
    defaultFlags.rewritePolyfills = true;
    defaultFlags.warningLevel = "DEFAULT";
    defaultFlags.useTypesForOptimization = true;
    defaultFlags.jsCode = null;
    defaultFlags.externs = null;
    defaultFlags.createSourceMap = true;
    defaultFlags.tracerMode = "OFF";
    defaultFlags.moduleResolutionMode = "BROWSER";
    defaultFlags.jsOutputFile = "compiled.js";
    defaultFlags.formatting = null;
    defaultFlags.errorFormat = "STANDARD";
    defaultFlags.sourceMapIncludeContent = false;
    defaultFlags.parseInlineSourceMaps = true;
    defaultFlags.chunk = null;
    defaultFlags.chunkWrapper = null;
    defaultFlags.chunkOutputPathPrefix = "./";
    return defaultFlags;
  }

  /** Properties here should match the AbstractCommandLineRunner.JsonFileSpec */
  @JsType(namespace = JsPackage.GLOBAL, name = "Object", isNative = true)
  private static class File {
    @JsProperty String path;
    @JsProperty String src;
    @JsProperty String sourceMap;
    @JsProperty String webpackId;
  }

  @JsType(namespace = JsPackage.GLOBAL, name = "Object", isNative = true)
  private static class ChunkOutput {
    @JsProperty @Deprecated String compiledCode;
    @JsProperty @Deprecated String sourceMap;
    @JsProperty File[] compiledFiles;
    @JsProperty JavaScriptObject[] errors;
    @JsProperty JavaScriptObject[] warnings;
  }

  /**
   * Reliably returns a string array from the flags/key combo.
   */
  private static native String[] getStringArray(Flags flags, String key) /*-{
    var value = flags[key];
    if (value == null) {
      return [];
    } else if (Array.isArray(value)) {
      return value;
    }
    return [value];
  }-*/;

  /**
   * Wraps a generic JS object used as a map.
   */
  private static final class JsMap extends JavaScriptObject {
    protected JsMap() {}

    /** @return This {@code JsMap} as a {@link Map}. */
    ImmutableMap<String, Object> asMap() {
      ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
      for (String key : keys(this)) {
        builder.put(key, get(key));
      }
      return builder.build();
    }

    /**
     * Validates that the values of this {@code JsMap} are primitives: either number, string or
     * boolean. Note that {@code typeof null} is object.
     */
    private native void validatePrimitiveTypes() /*-{
      var valid = {'number': '', 'string': '', 'boolean': ''};
      Object.keys(this).forEach(function(key) {
        var type = typeof this[key];
        if (!(type in valid)) {
          throw new TypeError('Type of define `' + key + '` unsupported: ' + type);
        }
      }, this);
    }-*/;

    private native Object get(String key) /*-{
      return this[key];
    }-*/;
  }

  @JsMethod(name = "keys", namespace = "Object")
  private static native String[] keys(Object o);

  private static native JavaScriptObject createError(String file, String description, String type,
        int lineNo, int charNo) /*-{
    return {file: file, description: description, type: type, lineNo: lineNo, charNo: charNo};
  }-*/;

  /**
   * Convert a list of {@link JSError} instances to a JS array containing plain objects.
   */
  private static JavaScriptObject[] toNativeErrorArray(List<JSError> errors) {
    JavaScriptObject[] out = new JavaScriptObject[errors.size()];
    for (int i = 0; i < errors.size(); ++i) {
      JSError error = errors.get(i);
      DiagnosticType type = error.getType();
      out[i] = createError(error.sourceName, error.description, type != null ? type.key : null,
          error.lineNumber, error.getCharno());
    }
    return out;
  }

  /** Generates the output code, taking into account the passed {@code flags}. */
  private static ChunkOutput writeChunkOutput(
      Compiler compiler, Flags flags, List<JSModule> chunks) {
    ArrayList<File> outputFiles = new ArrayList<>();
    ChunkOutput output = new ChunkOutput();

    Map<String, String> parsedModuleWrappers =
        CommandLineRunnerUtils.parseChunkWrappers(
            Arrays.asList(flags.chunkWrapper == null ? new String[] {} : flags.chunkWrapper),
            chunks);

    for (JSModule c : chunks) {
      if (flags.createSourceMap) {
        compiler.getSourceMap().reset();
      }

      File file = new File();
      file.path = flags.chunkOutputPathPrefix + c.getName() + ".js";

      String code = compiler.toSource(c);

      int lastSeparatorIndex = file.path.lastIndexOf('/');
      if (lastSeparatorIndex < 0) {
        lastSeparatorIndex = file.path.lastIndexOf('\\');
      }
      String baseName = file.path.substring(lastSeparatorIndex < 0 ? 0 : lastSeparatorIndex);
      String wrapper = parsedModuleWrappers.get(c.getName()).replace("%basename%", baseName);
      StringBuilder out = new StringBuilder();
      int pos = wrapper.indexOf("%s");
      if (pos != -1) {
        String prefix = "";

        if (pos > 0) {
          prefix = wrapper.substring(0, pos);
          out.append(prefix);
        }

        out.append(code);

        int suffixStart = pos + "%s".length();
        if (suffixStart != wrapper.length()) {
          // Something after placeholder?
          out.append(wrapper.substring(suffixStart));
        }
        // Make sure we always end output with a line feed.
        out.append('\n');

        // If we have a source map, adjust its offsets to match
        // the code WITHIN the wrapper.
        if (compiler != null && compiler.getSourceMap() != null) {
          compiler.getSourceMap().setWrapperPrefix(prefix);
        }

      } else {
        out.append(code);
        out.append('\n');
      }

      file.src = out.toString();

      if (flags.createSourceMap) {
        StringBuilder b = new StringBuilder();
        try {
          compiler.getSourceMap().appendTo(b, file.path);
        } catch (IOException e) {
          // ignore
        }
        file.sourceMap = b.toString();
      }
      outputFiles.add(file);
    }

    output.compiledFiles = outputFiles.toArray(new File[0]);
    return output;
  }

  /** Generates the output code, taking into account the passed {@code flags}. */
  private static ChunkOutput writeOutput(Compiler compiler, Flags flags) {
    ArrayList<File> outputFiles = new ArrayList<>();
    ChunkOutput output = new ChunkOutput();

    File file = new File();
    file.path = flags.jsOutputFile;

    String code = compiler.toSource();
    String prefix = "";
    String postfix = "";
    if (flags.outputWrapper != null) {
      String marker = null;
      int pos = flags.outputWrapper.indexOf(OUTPUT_MARKER_JS_STRING);
      if (pos != -1) {
        // With jsstring, run SourceCodeEscapers (as per AbstractCommandLineRunner).
        code = SourceCodeEscapers.javascriptEscaper().escape(code);
        marker = OUTPUT_MARKER_JS_STRING;
      } else {
        pos = flags.outputWrapper.indexOf(OUTPUT_MARKER);
        if (pos != -1) {
          marker = OUTPUT_MARKER;
        }
      }

      if (marker != null) {
        prefix = flags.outputWrapper.substring(0, pos);
        SourceMap sourceMap = compiler.getSourceMap();
        if (sourceMap != null) {
          sourceMap.setWrapperPrefix(prefix);
        }
      }
      postfix = flags.outputWrapper.substring(pos + marker.length());
    }
    if (flags.createSourceMap) {
      StringBuilder b = new StringBuilder();
      try {
        compiler.getSourceMap().appendTo(b, flags.jsOutputFile);
      } catch (IOException e) {
        // ignore
      }
      file.sourceMap = b.toString();
    }

    file.src = prefix + code + postfix;
    outputFiles.add(file);
    output.compiledFiles = outputFiles.toArray(new File[0]);
    output.compiledCode = file.src;
    output.sourceMap = file.sourceMap;
    return output;
  }

  private static List<SourceFile> createExterns(CompilerOptions.Environment environment) {
    String[] resources = ResourceLoader.resourceList(GwtRunner.class);
    Map<String, SourceFile> all = new HashMap<>();
    for (String res : resources) {
      if (res.startsWith(EXTERNS_PREFIX)) {
        String filename = res.substring(EXTERNS_PREFIX.length());
        all.put(filename, SourceFile.fromCode("externs.zip//" + res,
              ResourceLoader.loadTextResource(GwtRunner.class, res)));
      }
    }
    return DefaultExterns.prepareExterns(environment, all);
  }

  private static ImmutableList<ModuleIdentifier> createEntryPoints(String[] entryPoints) {
    ImmutableList.Builder<ModuleIdentifier> builder = new ImmutableList.Builder<>();
    for (String entryPoint : entryPoints) {
      if (entryPoint.startsWith("goog:")) {
        builder.add(ModuleIdentifier.forClosure(entryPoint));
      } else {
        builder.add(ModuleIdentifier.forFile(entryPoint));
      }
    }
    return builder.build();
  }

  private static DependencyOptions createDependencyOptions(
      CompilerOptions.DependencyMode dependencyMode,
      List<ModuleIdentifier> entryPoints) {
    // Copied from from AbstractCommandLineRunner.java.
    if (dependencyMode == CompilerOptions.DependencyMode.STRICT) {
      if (entryPoints.isEmpty()) {
        throw new RuntimeException(
            "When dependencyMode=STRICT, you must specify at least one entry point");
      }
      return new DependencyOptions()
          .setDependencyPruning(true)
          .setDependencySorting(true)
          .setMoocherDropping(true)
          .setEntryPoints(entryPoints);
    } else if (dependencyMode == CompilerOptions.DependencyMode.LOOSE || !entryPoints.isEmpty()) {
      return new DependencyOptions()
          .setDependencyPruning(true)
          .setDependencySorting(true)
          .setMoocherDropping(false)
          .setEntryPoints(entryPoints);
    }
    return null;
  }

  private static void applyDefaultOptions(CompilerOptions options) {
    CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
    WarningLevel.DEFAULT.setOptionsForWarningLevel(options);
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2017);
    options.setLanguageOut(LanguageMode.ECMASCRIPT5);
  }

  private static void applyOptionsFromFlags(CompilerOptions options, Flags flags) {
    CompilationLevel level = DEFAULT_COMPILATION_LEVEL;
    if (flags.compilationLevel != null) {
      level = CompilationLevel.fromString(Ascii.toUpperCase(flags.compilationLevel));
      if (level == null) {
        throw new RuntimeException(
            "Bad value for compilationLevel: " + flags.compilationLevel);
      }
    }
    if (level == CompilationLevel.ADVANCED_OPTIMIZATIONS && !flags.renaming) {
      throw new RuntimeException(
          "renaming cannot be disabled when ADVANCED_OPTIMIZATIONS is used");
    }
    level.setOptionsForCompilationLevel(options);
    if (flags.assumeFunctionWrapper) {
      level.setWrappedOutputOptimizations(options);
    }
    if (flags.useTypesForOptimization) {
      level.setTypeBasedOptimizationOptions(options);
    }

    WarningLevel warningLevel = WarningLevel.DEFAULT;
    if (flags.warningLevel != null) {
      warningLevel = WarningLevel.valueOf(flags.warningLevel);
    }
    warningLevel.setOptionsForWarningLevel(options);

    CompilerOptions.Environment environment = CompilerOptions.Environment.BROWSER;
    if (flags.env != null) {
      environment = CompilerOptions.Environment.valueOf(Ascii.toUpperCase(flags.env));
    }
    options.setEnvironment(environment);

    CompilerOptions.DependencyMode dependencyMode = CompilerOptions.DependencyMode.NONE;
    if (flags.dependencyMode != null) {
      dependencyMode =
          CompilerOptions.DependencyMode.valueOf(Ascii.toUpperCase(flags.dependencyMode));
    }
    List<ModuleIdentifier> entryPoints = createEntryPoints(getStringArray(flags, "entryPoint"));
    DependencyOptions dependencyOptions = createDependencyOptions(dependencyMode, entryPoints);
    if (dependencyOptions != null) {
      options.setDependencyOptions(dependencyOptions);
    }

    LanguageMode languageIn = LanguageMode.fromString(flags.languageIn);
    if (languageIn != null) {
      options.setLanguageIn(languageIn);
    }
    LanguageMode languageOut = LanguageMode.fromString(flags.languageOut);
    if (languageOut != null) {
      options.setLanguageOut(languageOut);
    }

    if (flags.createSourceMap) {
      options.setSourceMapOutputPath("%output%.map");
    }

    if (flags.defines != null) {
      // CompilerOptions also validates types, but uses Preconditions and therefore won't generate
      // a useful exception.
      flags.defines.validatePrimitiveTypes();
      options.setDefineReplacements(flags.defines.asMap());
    }

    if (flags.extraAnnotationNames != null) {
      options.setExtraAnnotationNames(Arrays.asList(flags.extraAnnotationNames));
    }

    if (flags.tracerMode != null) {
      options.setTracerMode(TracerMode.valueOf(flags.tracerMode));
    }

    if (flags.moduleResolutionMode != null) {
      options.setModuleResolutionMode(ResolutionMode.valueOf(flags.moduleResolutionMode));
    }

    if (flags.isolationMode != null
        && IsolationMode.valueOf(flags.isolationMode) == IsolationMode.IIFE) {
      flags.outputWrapper = "(function(){%output%}).call(this);";
      flags.assumeFunctionWrapper = true;
    }

    if (flags.formatting != null) {
      List<String> formattingOptions = Arrays.asList(getStringArray(flags, "formatting"));
      for (String formattingOption : formattingOptions) {
        switch (formattingOption) {
          case "PRETTY_PRINT":
            options.setPrettyPrint(true);
            break;
          case "PRINT_INPUT_DELIMITER":
            options.printInputDelimiter = true;
            break;
          case "SINGLE_QUOTES":
            options.setPreferSingleQuotes(true);
            break;
          default:
            throw new RuntimeException("Unknown formatting option: " + formattingOption);
        }
      }
    }

    // Only one error format is supported by the JS version. However support the flag as a
    // noop to make it easy to switch between the Java and JS versions.
    if (flags.errorFormat != null
        && flags.errorFormat != "STANDARD"
        && flags.errorFormat != "JSON") {
      throw new RuntimeException("Unknown errorFormat option: " + flags.errorFormat);
    }

    options.setSourceMapIncludeSourcesContent(flags.sourceMapIncludeContent);
    options.setParseInlineSourceMaps(flags.parseInlineSourceMaps);
    options.setAngularPass(flags.angularPass);
    options.setApplyInputSourceMaps(flags.applyInputSourceMaps);
    options.setChecksOnly(flags.checksOnly);
    options.setDartPass(flags.dartPass);
    options.setExportLocalPropertyDefinitions(flags.exportLocalPropertyDefinitions);
    options.setGenerateExports(flags.generateExports);
    if (flags.polymerPass) {
      options.setPolymerVersion(1);
    } else if (flags.polymerVersion != null) {
      options.setPolymerVersion(flags.polymerVersion.intValue());
    }
    options.setPreserveTypeAnnotations(flags.preserveTypeAnnotations);
    options.setClosurePass(flags.processClosurePrimitives);
    options.setProcessCommonJSModules(flags.processCommonJsModules);
    options.setRenamePrefixNamespace(flags.renamePrefixNamespace);
    if (!flags.renaming) {
      options.setVariableRenaming(VariableRenamingPolicy.OFF);
      options.setPropertyRenaming(PropertyRenamingPolicy.OFF);
    }
  }

  private static void disableUnsupportedOptions(CompilerOptions options) {
    options.getDependencyOptions().setDependencySorting(false);
  }

  private static List<SourceFile> fromFileArray(File[] src, String unknownPrefix) {
    List<SourceFile> out = new ArrayList<>();
    if (src != null) {
      for (int i = 0; i < src.length; ++i) {
        File file = src[i];
        String path = file.path;
        if (path == null) {
          path = unknownPrefix + i;
        }
        out.add(SourceFile.fromCode(path, nullToEmpty(file.src)));
      }
    }
    return out;
  }

  private static ImmutableMap<String, SourceMapInput> buildSourceMaps(
      File[] src, String unknownPrefix) {
    ImmutableMap.Builder<String, SourceMapInput> inputSourceMaps = new ImmutableMap.Builder<>();
    if (src != null) {
      for (int i = 0; i < src.length; ++i) {
        File file = src[i];
        if (isNullOrEmpty(file.sourceMap)) {
          continue;
        }
        String path = file.path;
        if (path == null) {
          path = unknownPrefix + i;
        }
        SourceFile sf = SourceFile.fromCode(path + ".map", file.sourceMap);
        inputSourceMaps.put(path, new SourceMapInput(sf));
      }
    }
    return inputSourceMaps.build();
  }

  /**
   * Updates the destination flags (user input) with source flags (the defaults). Returns a list
   * of flags that are on the destination, but not on the source.
   */
  private static native String[] updateFlags(Flags dst, Flags src) /*-{
    for (var k in src) {
      if (!(k in dst)) {
        dst[k] = src[k];
      }
    }
    var unhandled = [];
    for (var k in dst) {
      if (!(k in src)) {
        unhandled.push(k);
      }
    }
    return unhandled;
  }-*/;

  /** Public compiler call. Exposed in {@link #exportCompile}. */
  @JsMethod(namespace = "jscomp")
  public static ChunkOutput compile(Flags flags, File[] inputs) throws IOException {
    String[] unhandled = updateFlags(flags, getDefaultFlags());
    if (unhandled.length > 0) {
      throw new RuntimeException("Unhandled flag: " + unhandled[0]);
    }

    List<SourceFile> jsCode = null;
    ImmutableMap<String, SourceMapInput> sourceMaps = null;
    if (flags.jsCode != null) {
      jsCode = fromFileArray(flags.jsCode, "Input_");
      sourceMaps = buildSourceMaps(flags.jsCode, "Input_");
    }

    ImmutableMap.Builder<String, String> inputPathByWebpackId = new ImmutableMap.Builder<>();
    if (inputs != null) {
      List<SourceFile> sourceFiles = fromFileArray(inputs, "Input_");
      ImmutableMap<String, SourceMapInput> inputSourceMaps = buildSourceMaps(inputs, "Input_");
      if (jsCode == null) {
        jsCode = sourceFiles;
      } else {
        jsCode.addAll(sourceFiles);
      }

      if (sourceMaps == null) {
        sourceMaps = inputSourceMaps;
      } else {
        HashMap<String, SourceMapInput> tempMaps = new HashMap<>(sourceMaps);
        tempMaps.putAll(inputSourceMaps);
        sourceMaps = ImmutableMap.copyOf(tempMaps);
      }

      for (int i = 0; i < inputs.length; i++) {
        if (inputs[i].webpackId != null && inputs[i].path != null) {
          inputPathByWebpackId.put(inputs[i].webpackId, inputs[i].path);
        }
      }
    }

    CompilerOptions options = new CompilerOptions();
    applyDefaultOptions(options);
    applyOptionsFromFlags(options, flags);
    options.setInputSourceMaps(sourceMaps);
    disableUnsupportedOptions(options);

    List<SourceFile> externs = fromFileArray(flags.externs, "Extern_");
    externs.addAll(createExterns(options.getEnvironment()));

    NodeErrorManager errorManager = new NodeErrorManager();
    Compiler compiler = new Compiler(new NodePrintStream());
    compiler.initWebpackMap(inputPathByWebpackId.build());
    compiler.setErrorManager(errorManager);

    List<String> chunkSpecs = new ArrayList<>();
    if (flags.chunk != null) {
      chunkSpecs.addAll(Arrays.asList(flags.chunk));
    }
    List<JsModuleSpec> jsChunkSpecs = new ArrayList<>();
    for (int i = 0; i < chunkSpecs.size(); i++) {
      jsChunkSpecs.add(JsModuleSpec.create(chunkSpecs.get(i), i == 0));
    }
    ChunkOutput output;
    if (jsChunkSpecs.size() > 0) {
      List<JSModule> chunks = CommandLineRunnerUtils.createJsModules(jsChunkSpecs, jsCode);

      compiler.compileModules(externs, chunks, options);
      output = writeChunkOutput(compiler, flags, chunks);
    } else {
      compiler.compile(externs, jsCode, options);
      output = writeOutput(compiler, flags);
    }

    output.errors = toNativeErrorArray(errorManager.errors);
    output.warnings = toNativeErrorArray(errorManager.warnings);

    return output;
  }

  /**
   * Exports the {@link #compile} method via JSNI.
   *
   * <p>This will be placed on {@code module.exports}, {@code self.compile} or {@code
   * window.compile}.
   */
  public native void exportCompile() /*-{
    var fn = $entry(@com.google.javascript.jscomp.gwt.client.GwtRunner::compile(*));
    if (typeof module !== 'undefined' && module.exports) {
      module.exports = fn;
    } else if (typeof self === 'object') {
      self.compile = fn;
    } else {
      window.compile = fn;
    }
  }-*/;

  /**
   * Custom {@link BasicErrorManager} to record {@link JSError} instances.
   */
  private static class NodeErrorManager extends BasicErrorManager {
    final List<JSError> errors = new ArrayList<>();
    final List<JSError> warnings = new ArrayList<>();

    @Override
    public void println(CheckLevel level, JSError error) {
      if (level == CheckLevel.ERROR) {
        errors.add(error);
      } else if (level == CheckLevel.WARNING) {
        warnings.add(error);
      }
    }

    @Override
    public void printSummary() {}
  }

  // TODO(johnlenz): remove this once GWT has a proper PrintStream implementation
  private static class NodePrintStream extends PrintStream {
    private String line = "";

    NodePrintStream() {
      super((OutputStream) null);
    }

    @Override
    public void println(String s) {
      print(s + "\n");
    }

    @Override
    public void print(String s) {
      if (useStdErr()) {
        writeToStdErr(s);
      } else {
        writeFinishedLinesToConsole(s);
      }
    }

    private void writeFinishedLinesToConsole(String s) {
      line = line + s;
      int start = 0;
      int end = 0;
      while ((end = line.indexOf('\n', start)) != -1) {
        writeToConsole(line.substring(start, end));
        start = end + 1;
      }
      line = line.substring(start);
    }

    private static native boolean useStdErr() /*-{
      return !!(typeof process != "undefined" && process.stderr);
    }-*/;

    private native boolean writeToStdErr(String s) /*-{
      process.stderr.write(s);
    }-*/;


    // NOTE: console methods always add a newline following the text.
    private native void writeToConsole(String s) /*-{
       console.log(s);
    }-*/;

    @Override
    public void close() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
    }

    @Override
    public void write(int oneByte) {
    }
  }
}
