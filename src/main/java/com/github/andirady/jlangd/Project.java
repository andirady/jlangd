package com.github.andirady.jlangd;

import static java.util.stream.Collectors.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;
import java.util.concurrent.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import javax.tools.*;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.*;

public class Project {

    record Type(String simpleName, String canonicalName) {}

    record CompilationResult(
            JavacTask task,
            Iterable<? extends CompilationUnitTree> trees) {
        
        public Trees treesUtil() {
            return Trees.instance(task);
        }
        
        public Stream<? extends CompilationUnitTree> streamTrees() {
            return StreamSupport.stream(trees.spliterator(), false);
        }

        public Optional<? extends CompilationUnitTree> findTreeForUri(String uri) {
            return streamTrees().filter(t -> t.getSourceFile().toUri().toString().equals(uri))
                                .findFirst();
        }

        public boolean hasSourceForUri(String uri) {
            for (var cu : trees) {
                if (cu.getSourceFile().toUri().toString().equals(uri)) {
                    return true;
                }
            }

            return false;
        }
    }

    private static final Logger LOG = Logger.getLogger(Project.class.getName());
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    private final Path path;
    private final Map<String, JavaSource> sources;
    private final JavaFileManager fileMgr;
    private final String classpath;
    private final String modulepath;
    private final Path srcDir;
    private final Path outputDir;
    
    private CompilationResult previousCompilationResult;
    private Consumer<List<Diagnostic<? extends JavaFileObject>>> diagnosticConsumer;
    private Map<String, CompilationUnitTree> compilationUnits;

    // Lazy hack
    JavaTextDocumentService textDocService;

    public Project(Path path,
            Charset charset,
            List<Path> classpath,
            List<Path> modulePath,
            Path srcDir,
            Path outputDir) {
        this.path = path;
        sources = new HashMap<>();
        fileMgr = COMPILER.getStandardFileManager(null, null, charset);
        classpath.forEach(p -> LOG.info(() -> p + " added to classpath"));
        this.classpath = Stream.concat(classpath.stream(), Stream.ofNullable(outputDir))
                               .map(Path::toString)
                               .collect(joining(System.getProperty("path.separator")));
        this.modulepath = modulePath.stream().map(Path::toString).collect(joining(System.getProperty("path.separator")));
        this.srcDir = srcDir;
        this.outputDir = outputDir;
        this.compilationUnits = new HashMap<>();
    }

    public Path path() {
        return path;
    }

    public void setDiagnosticsConsumer(Consumer<List<Diagnostic<? extends JavaFileObject>>> consumer) {
        this.diagnosticConsumer = consumer;
    }

    JavaSource addJavaSource(String uri, String text) {
        LOG.fine(() -> "Added " + uri);
        var source = new JavaSource(URI.create(uri), text);
        sources.put(uri, source);
        return source;
    }

    public Stream<Path> srcDirs() {
        // TODO replace srcDir to a list
        return Stream.of(srcDir);
    }

    public java.util.stream.Stream<String> streamSourceUris() {
        return sources.keySet().stream();
    }

    public Optional<JavaSource> getSource(String uri) {
        return Optional.ofNullable(sources.get(uri));
    }
    
    public List<Diagnostic<? extends JavaFileObject>> compileAll() {
        var targets = streamSourceUris()
                .map(URI::create)
                .map(Path::of)
                .filter(p -> p.startsWith(srcDir))
                .map(Path::toUri)
                .map(URI::toString)
                .toList();
        return fullCompile(targets);
    }

    public List<Diagnostic<? extends JavaFileObject>> diagnose(String uri) {
        return diagnose(List.of(uri));
    }
    
    List<Diagnostic<? extends JavaFileObject>> diagnose(List<String> uris) {
        return diagnoseSources(sources.entrySet().stream()
                .filter(e -> uris.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList());
    }

    private synchronized List<Diagnostic<? extends JavaFileObject>> diagnoseSources(List<? extends JavaFileObject> targets) {
        var t0 = System.currentTimeMillis();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) COMPILER.getTask(null, fileMgr, diags, options(), null, targets);
        var findUnused = new FindUnused(task);
        
        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ANALYZE) {
                    CompletableFuture.runAsync(() -> {
                        var t0 = System.currentTimeMillis();
                        var trees = Trees.instance(task);
                        var sourcePositions = trees.getSourcePositions();
                        var tree = event.getCompilationUnit();
                        var lineMap = tree.getLineMap();
                        var diags = findUnused.find(event)
                                              .stream()
                                              .map(n -> {
                                                  var sp = sourcePositions.getStartPosition(tree, n);
                                                  var ep = sourcePositions.getEndPosition(tree, n);
                                                  var start = Util.encodePosition(lineMap, sp);
                                                  var end = Util.encodePosition(lineMap, ep);
                                                  var range = new Range(start, end);
                                                  var d = new org.eclipse.lsp4j.Diagnostic(range, "never used");
                                                  d.setSeverity(DiagnosticSeverity.Hint);
                                                  d.setTags(List.of(DiagnosticTag.Unnecessary));
                                                  return d;
                                              })
                                              .toList();

                        if (!diags.isEmpty()) {
                            var params = new PublishDiagnosticsParams(tree.getSourceFile().toUri().toString(), diags);
                            textDocService.publishDiagnostics(params);
                        }

                        LOG.fine(() -> "Find unused took " + (System.currentTimeMillis() - t0) + " ms");
                    });
                }
            }
        });
        try {
            var trees = task.parse();
            diagnosticConsumer.accept(diags.getDiagnostics());

            if (diags.getDiagnostics().isEmpty()) {
                task.analyze();
                diagnosticConsumer.accept(diags.getDiagnostics());
            }

            LOG.fine(() -> "Diagnosed in " + (System.currentTimeMillis() - t0) + " ms");
            previousCompilationResult = new CompilationResult(task, trees);
            trees.forEach(t -> compilationUnits.put(t.getSourceFile().toUri().toString(), t));

            return diags.getDiagnostics();
        } catch (IOException e) {
            LOG.fine(e::getMessage);
            throw new UncheckedIOException(e);
        }
    }

    Optional<JavacTask> previousTask() {
        return Optional.ofNullable(previousCompilationResult).map(CompilationResult::task);
    }

    synchronized List<Diagnostic<? extends JavaFileObject>> fullCompile(List<String> uris) {
        var targets = uris.stream()
                          .map(sources::get)
                          .filter(Objects::nonNull)
                          .toList();
        if (targets.isEmpty()) {
            return List.of();
        }

        var diags = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) COMPILER.getTask(null, fileMgr, diags, options(), null, targets);
        try {
            task.parse();
            if (diags.getDiagnostics().isEmpty()) {
                task.analyze();

                if (diags.getDiagnostics().isEmpty() && options().contains("-d")) {
                    //task.generate();

                    //fileMgr.flush();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        diagnosticConsumer.accept(diags.getDiagnostics());

        return diags.getDiagnostics();
    }
    
    String classpathString() {
        return classpath;
    }

    private List<String> options() {
        if (outputDir != null) {
            return List.of(
                "--class-path", classpath,
                "--module-path", modulepath,
                "-d", outputDir.toString(),
                "-nowarn",
                "-parameters",
                "-XDrawDiagnostics"
            );
        }

        return List.of(
            "--class-path", classpath,
            "--module-path", modulepath,
            "-nowarn",
            "-parameters",
            "-XDrawDiagnostics"
        );
    }

    Optional<JavaSource> getJavaSource(String uri) {
        return Optional.ofNullable(sources.get(uri));
    }

    void removeJavaSource(String uri) {
        sources.remove(uri);
    }

    synchronized CompilationResult taskForUri(String uri) {
        if (previousCompilationResult == null || !previousCompilationResult.hasSourceForUri(uri)) {
            diagnose(List.of(uri));
        }

        return previousCompilationResult;
    }
    
    CompilationUnitTree compilationUnitFor(String uri) {
        var tree = compilationUnits.get(uri);
        if (tree == null) {
            diagnose(uri);
            tree = compilationUnits.get(uri);
        }

        return tree;
    }

    CompilationUnitTree compilationUnitArbiraryUri(String uri) {
        var uriObj = URI.create(uri);
        JavaFileObject source;
        try {
            source = new JavaSource(uriObj, Files.readString(Path.of(uriObj)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        diagnoseSources(List.of(source));

        return previousCompilationResult.findTreeForUri(uri).orElseThrow();
    }
    
    Optional<URI> findUriForType(TypeElement elem) {
        // TODO get from file manager
        var pathSep = FileSystems.getDefault().getSeparator();
        var suffix = Path.of(Pattern.compile("\\.").splitAsStream(elem.toString())
                .collect(Collectors.joining(pathSep)) + ".java");
        
        if (Files.exists(srcDir.resolve(suffix))) {
            return Optional.of(srcDir.resolve(suffix).toUri());
        }
        
        return Optional.empty();
    }

    public String getPackageForUri(String uri) {
        var path = Path.of(URI.create(uri));
        return srcDirs().filter(path::startsWith)
                        .map(p -> p.relativize(path))
                        .flatMap(p -> StreamSupport.stream(p.getParent().spliterator(), false))
                        .map(Path::toString)
                        .collect(Collectors.joining("."));
    }

    public TypeElement getTypeElement(Elements elementsUtil, CharSequence canonName) {
        // The Elements#getTypeElement mutates the state of the fileMgr, hence
        // allow only a single thread at a time.
        synchronized(fileMgr) {
            return (TypeElement) elementsUtil.getTypeElement(canonName);
        }
    }

    public void close() {
        try {
            fileMgr.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        sources.clear();
    }

}
