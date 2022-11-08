/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.github.andirady.jlangd;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import static java.util.stream.Collectors.*;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;
import org.eclipse.lsp4j.services.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

/**
 *
 * @author andirady
 */
public class JavaTextDocumentService implements TextDocumentService,
                                                LanguageClientAware,
                                                Consumer<List<javax.tools.Diagnostic<? extends JavaFileObject>>> {

    private static final Logger LOG = Logger.getLogger(JavaTextDocumentService.class.getName());

    LanguageClient client;

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }
    
    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var textDoc = params.getTextDocument();
        var uri = textDoc.getUri();
        LOG.info(() -> "Opening " + uri + " version " + textDoc.getVersion());
        var project = Projects.forUri(uri);
        project.addJavaSource(uri, textDoc.getText());

        project.textDocService = this;
        diagnoseSingle(project, uri);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        var project = Projects.forUri(uri);
        var source = project
                .getJavaSource(uri)
                .orElseGet(() -> {
                    // This should not happen if the client follows the protocol spec.
                    LOG.warning(() -> "No source found for " + uri + ", manually opening it...");
                    try {
                        var text = Files.readString(Path.of(URI.create(uri)));
                        return project.addJavaSource(uri, text);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        params.getContentChanges().forEach(e -> source.patch(e.getRange(), e.getText()));
        diagnoseSingle(project, uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        Projects.forUri(uri).removeJavaSource(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        var uri = params.getTextDocument().getUri();
        LOG.fine(() -> uri + " saved");
        var project = Projects.forUri(uri);
        compileAll(project);
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        LOG.fine(params::toString);
        return CompletableFuture.supplyAsync(() -> doCodeAction(params))
                                .thenApply(Stream::toList);
    }

    private Stream<Either<Command, CodeAction>> doCodeAction(CodeActionParams params) {
        var uri = params.getTextDocument().getUri();
        var project = Projects.forUri(uri);

        var projectTask = project.taskForUri(uri);
        var tree = projectTask.findTreeForUri(uri).orElseThrow();
        var range = params.getRange();
        var start = range.getStart();
        var lineMap = tree.getLineMap();
        var trees = projectTask.treesUtil();
        var sourcePositions = trees.getSourcePositions();

        var cursor = Util.decodePosition(lineMap, start);
        var line = range.getStart().getLine() + 1L;
        var futures = new ArrayList<CompletableFuture<Either<Command, CodeAction>>>();

        var source = project.getSource(uri).orElseThrow();
        CharSequence content;
        try {
           content = source.getCharContent(false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        var pkg = tree.getPackage();
        var pkgDeclarationEnd = pkg == null ? 0 : (sourcePositions.getEndPosition(tree, pkg));
        for (var diag : params.getContext().getDiagnostics()) {
            switch (diag.getCode().getLeft()) {
                case "compiler.err.cant.resolve.location",
                     "compiler.err.doesnt.exist":
                    var dStart = diag.getRange().getStart();
                    var dEnd = diag.getRange().getEnd();
                    var symbol = content.subSequence(source.lineOffset(dStart.getLine()) + dStart.getCharacter(),
                                                     source.lineOffset(dEnd.getLine()) + dEnd.getCharacter());
                    LOG.fine(() -> "symbol = " + symbol);
                    var pattern = Pattern.compile("^(.*[.]|)" + symbol + "$");
                    var addImport = new AddImport(tree, trees);
                    LoadTypes.streamTypeNames(project.classpathString(), s -> pattern.matcher(s).matches())
                             .distinct()
                             .forEach(s -> futures.add(CompletableFuture.supplyAsync(() -> {
                                               var ca = new CodeAction("Import " + s);
                                               var we = new WorkspaceEdit(Map.of(uri, List.of(addImport.addImport(s))));
                                               ca.setEdit(we);
                                               return Either.forRight(ca);
                                           })));
                    break;
                default:
                    continue;
            }
        }

        Predicate<Tree> cursorInNode = node -> {
            var ep = sourcePositions.getEndPosition(tree, node);
            if (ep < cursor)
                return false;

            var sp = sourcePositions.getStartPosition(tree, node);
            return cursor >= sp && cursor <= ep;
        };

        Predicate<Tree> rangeFilter = node -> {
            var ep = sourcePositions.getEndPosition(tree, node);
            if (ep < cursor)
                return false;

            var sp = sourcePositions.getStartPosition(tree, node);
            var nodeLine = lineMap.getLineNumber(sp);
            return nodeLine == line;
        };

        var finder = new TreeScanner<Void, Void>() {

            @Override
            public Void visitIdentifier(IdentifierTree node, Void p) {
                if (cursorInNode.test(node)) {

                    futures.add(CompletableFuture.supplyAsync(() -> {
                        var elem = trees.getElement(trees.getPath(tree, node));
                        if (switch (elem.getKind()) {
                            case BINDING_VARIABLE, LOCAL_VARIABLE -> false;
                            default -> true;
                        }) {
                            return null;
                        }

                        var declarationTree = trees.getTree(elem);
                        var ca = new CodeAction("Inline variable `" + node.getName() + "`");
                        var we = new WorkspaceEdit();
                        var idStartPos = sourcePositions.getStartPosition(tree, node);
                        var idEndPos = sourcePositions.getEndPosition(tree, node);
                        var idStart = Util.encodePosition(lineMap, idStartPos);
                        var idEnd = Util.encodePosition(lineMap, idEndPos);
                        var idRange = new Range(idStart, idEnd);

                        String label;
                        if (declarationTree instanceof VariableTree varTree) {
                            var init = varTree.getInitializer();
                            if (init == null) {
                                return null; // supports only vars with init.
                            }

                            label = init.toString();
                        } else {
                            LOG.fine(() -> String.format("Unknown declaration tree %s of class %s",
                                                         declarationTree,
                                                         declarationTree.getClass().getCanonicalName()));
                            label = node.toString();
                        }

                        we.setChanges(Map.of(uri, List.of(new TextEdit(idRange, label))));
                        ca.setEdit(we);
                        LOG.fine(() -> "Created " + ca);

                        return Either.forRight(ca);
                    }));
                }

                return null;
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void p) {
                if (cursorInNode.test(node)) {
                    var ctx = params.getContext();
                    if (ctx == null) {
                        return null;
                    }

                }

                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void p) {
                if (rangeFilter.test(node)) {
                    var sp = sourcePositions.getStartPosition(tree, node);

                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            var source = tree.getSourceFile().getCharContent(false);
                            // FIXME handle modifiers
                            var inferred = source.subSequence((int) sp, (int) sp + 4)
                                                 .toString()
                                                 .startsWith("var ");
                            var sc = new TreeScanner<Void, Void>() {

                                @Override
                                public Void scan(Tree n, Void p) {
                                    if (n != null) {
                                        var s = sourcePositions.getStartPosition(tree, n)
                                              + ", " + sourcePositions.getEndPosition(tree, n);
                                        LOG.fine(() -> "SC node: " + n + ", " + n.getKind() + "(" + s + ")");
                                    }

                                    return null;
                                }
                            };
                            node.accept(sc, null);
                            var t = node.getType();
                            var typeName = (t instanceof MemberSelectTree ms ? ms.getIdentifier() : t).toString();
                            // FIXME handle anonymous properly

                            if (!inferred) {
                                var ca = new CodeAction("Use var type for `" + node.getName() + "`");
                                var pos = Util.encodePosition(lineMap, sp);
                                var range = new Range(pos, new Position(pos.getLine(), pos.getCharacter() + 3));
                                var we = new WorkspaceEdit();
                                we.setChanges(Map.of(uri, List.of(new TextEdit(range, typeName))));
                                ca.setEdit(we);

                                //TODO
                                //return Either.forRight(ca);
                                return null;
                            } else {
                                var ca = new CodeAction("Use explicit type for `" + node.getName() + "`");
                                var pos = Util.encodePosition(lineMap, sp);
                                var range = new Range(pos, new Position(pos.getLine(), pos.getCharacter() + 3));
                                var we = new WorkspaceEdit();
                                we.setChanges(Map.of(uri, List.of(new TextEdit(range, typeName))));
                                ca.setEdit(we);

                                return Either.forRight(ca);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }));
                }

                return super.visitVariable(node, p);
            }

        };
        
        tree.accept(finder, null);

        return futures.stream()
                      .map(f -> {
                          try {
                              return f.get();
                          } catch (Exception e) {
                              throw new IllegalStateException(e);
                          }
                      })
                      .filter(Objects::nonNull);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        //LOGGER.fine(params::toString);
        var uri = params.getTextDocument().getUri();
        var project = Projects.forUri(uri);
        
        var t0 = System.currentTimeMillis();
        var service = new SuggestCompletion(project, uri);
        
        return CompletableFuture.supplyAsync(() -> service.complete(params.getPosition()))
                                .thenApply(s -> s.map(this::resolveCompletionItem))
                                .thenApply(s -> s.map(i -> {
                                    try {
                                        var updated = i.get();
                                        LOG.fine(updated::toString);
                                        return updated;
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                }))
                                .thenApply(Stream::toList)
                                .handle((list, ex) -> {
                                    LOG.fine(() -> "Suggestions computed in " + (System.currentTimeMillis() - t0) + " ms");
                                    if (ex == null)
                                        return Either.forLeft(list);

                                    LOG.log(Level.SEVERE, "Failed to compute suggestions", ex.getCause());
                                    return Either.forLeft(List.of());
                                });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        var kind = item.getKind();

        if (( kind == CompletionItemKind.Interface
           || kind == CompletionItemKind.Class
           || kind == CompletionItemKind.Enum)
           && item.getData() instanceof String uri) {
            return CompletableFuture.supplyAsync(() -> ensureImported(uri, item));
        }

        return CompletableFuture.completedFuture(item);
    }

    private CompletionItem ensureImported(String uri, CompletionItem item) {
        var project = Projects.forUri(uri);
        var compilationResult = project.taskForUri(uri);
        var tree = compilationResult.findTreeForUri(uri).orElseThrow();
        var imports = tree.getImports();
        if (imports.stream()
                   .filter(imp -> !imp.isStatic())
                   .map(imp -> imp.getQualifiedIdentifier().toString())
                   .filter(imp -> imp.startsWith(item.getDetail() + "."))
                   .noneMatch(imp -> imp.endsWith(".*") || imp.endsWith(item.getLabel()))) {
            var pkg = item.getDetail();
            var edit = new TextEdit();
            edit.setNewText("import " + pkg + "." + item.getLabel() + ";");

            var trees = compilationResult.treesUtil();
            var sourcePositions = trees.getSourcePositions();

            long pos;
            if (imports.isEmpty()) {
                var pkgTree = tree.getPackage();
                pos = sourcePositions.getEndPosition(tree, pkgTree);
            } else {
                var lastImport = imports.get(imports.size() - 1);
                pos = sourcePositions.getEndPosition(tree, lastImport);
            }

            var lineMap = tree.getLineMap();
            var start = Util.encodePosition(lineMap, pos);
            edit.setRange(new Range(start, start));
            item.setAdditionalTextEdits(List.of(edit));
        }

        LOG.fine(item::toString);
        return item;
    }

    private void diagnoseSingle(Project project, String uri) {
        CompletableFuture.runAsync(() -> {
            var t0 = System.currentTimeMillis();
            var result = project.diagnose(uri);
            var elapsed = System.currentTimeMillis() - t0;
            var msg = "Diagnosed " + Path.of(URI.create(uri)) + " in " + elapsed + " ms.";
            if (result.isEmpty()) {
                client.logMessage(new MessageParams(MessageType.Info, "OK. " + msg));
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
                return;
            }

            client.logMessage(new MessageParams(MessageType.Error, "Failed. " + msg));
        });
    }

    @Override
    public void accept(List<javax.tools.Diagnostic<? extends JavaFileObject>> diags) {
        diags.stream()
             .peek(d -> LOG.fine(() -> "d.source: " + d.getSource()))
             .filter(d -> d.getSource() != null)
             .collect(groupingBy(d -> d.getSource().toUri().toString(), mapping(this::lspDiag, toList())))
             .entrySet()
             .stream()
             .map(e -> new PublishDiagnosticsParams(e.getKey(), e.getValue()))
             .forEach(client::publishDiagnostics);
    }

    void publishDiagnostics(PublishDiagnosticsParams params) {
        client.publishDiagnostics(params);
    }

    private void compileAll(Project project) {
        CompletableFuture.runAsync(() -> {
            var t0 = System.currentTimeMillis();
            var result = project.compileAll();
            var msg = "Compilation took " + (System.currentTimeMillis() - t0) + " ms.";
            if (result.isEmpty()) {
                client.logMessage(new MessageParams(MessageType.Info, "OK. " + msg));
                project.streamSourceUris()
                       .map(uri -> new PublishDiagnosticsParams(uri, List.of()))
                       .forEach(client::publishDiagnostics);
                return;
            }

            client.logMessage(new MessageParams(MessageType.Error, "Failed. " + msg));
        });
    }

    private Diagnostic lspDiag(javax.tools.Diagnostic<? extends JavaFileObject> diag) {
        if (!(diag.getSource() instanceof JavaSource source)) {
            return null;
        }

        var startOffset = (int) diag.getPosition();
        var endOffset = (int) diag.getEndPosition();
        var range = new Range(source.lspPosition(startOffset), source.lspPosition(endOffset));
        var severity = switch (diag.getKind()) {
            case ERROR ->
                DiagnosticSeverity.Error;
            case MANDATORY_WARNING, WARNING ->
                DiagnosticSeverity.Warning;
            case NOTE, OTHER ->
                DiagnosticSeverity.Information;
        };
        
        var lspDiag = new Diagnostic(range, diag.getMessage(null), severity, "javac");
        lspDiag.setCode(diag.getCode());
        return lspDiag;
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return new GotoDefinition().go(params);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return new GetHover().hover(params);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        // TODO
        return TextDocumentService.super.references(params);
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        var uri = params.getTextDocument().getUri();
        var project = Projects.forUri(uri);
        var rename = new RenameSymbol(project, uri);

        return CompletableFuture.supplyAsync(() -> rename.rename(params.getPosition(), params.getNewName()));
    }
}
