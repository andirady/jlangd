package com.github.andirady.jlangd;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;
import javax.lang.model.element.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;

public class GotoDefinition {

    private static final Logger LOG = Logger.getLogger(GotoDefinition.class.getName());

    private Project project;
    private String uri;
    private CompilationUnitTree tree;
    private Trees treesUtil;
    private SourcePositions sourcePositions;

    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
           go(DefinitionParams params) {
        this.uri = params.getTextDocument().getUri();
        this.project = Projects.forUri(uri);
        this.tree = project.compilationUnitFor(uri);
        var task = project.previousTask().orElseThrow();
        this.treesUtil = Trees.instance(task);
        this.sourcePositions = treesUtil.getSourcePositions();

        var lineMap = tree.getLineMap();
        var cursor = (long) Util.decodePosition(lineMap, params.getPosition());
        var scanner = new Scanner();
        scanner.scan(tree, cursor);

        return !scanner.futures.isEmpty()
               ? scanner.futures.get(0)
               : CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    Optional<Location> locationForType(TypeElement elem) {
        var foreignUri = project.findUriForType(elem).map(Object::toString).orElse(null);

        if (foreignUri == null) 
            return Optional.empty();

        var hasSource = project.getSource(foreignUri);
        var foreignTree = hasSource.isPresent()
                        ? project.compilationUnitFor(foreignUri)
                        : project.compilationUnitArbiraryUri(foreignUri);
        var location = new ArrayList<Location>(1);
        var foreignScanner = new TreeScanner<Void, Void>() {

            @Override
            public Void visitClass(ClassTree node, Void p) {
                LOG.fine(() -> "node: " + node);
                var simpleName = node.getSimpleName();
                if (node.getSimpleName().equals(simpleName)) {
                    location.add(locationFor(foreignUri, foreignTree, node, simpleName::toString));
                }
                return null;
            }
        };

        foreignTree.accept(foreignScanner, null);

        return location.stream().findFirst();
    }

    Optional<Location> locationForMethod(ExecutableElement elem) {
        if (!(elem.getEnclosingElement() instanceof TypeElement type))
            return Optional.empty();

        var foreignUri = project.findUriForType(type).map(Object::toString).orElse(null);

        if (foreignUri == null)
            return Optional.empty();

        var hasSource = project.getSource(foreignUri);
        var foreignTree = hasSource.isPresent()
                        ? project.compilationUnitFor(foreignUri)
                        : project.compilationUnitArbiraryUri(foreignUri);
        var location = new ArrayList<Location>(1);
        var foreignScanner = new TreeScanner<Location, Void>() {

            @Override
            public Location visitMethod(MethodTree n, Void p) {
                if (n.getKind() != Tree.Kind.METHOD)
                    return null;

                var path = treesUtil.getPath(foreignTree, n);
                var foreignElem = (ExecutableElement) treesUtil.getElement(path);
                var same = sameMethod(elem, foreignElem);
                if (same) {
                    LOG.fine(() -> "Found matching element for " + elem  + " in " + foreignUri);
                    location.add(locationFor(foreignUri, foreignTree, n,
                                             foreignElem.getSimpleName()::toString));
                }

                return null;
            }

            boolean sameMethod(ExecutableElement exec1, ExecutableElement exec2) {
                if (!exec1.toString().equals(exec2.toString()))
                    return false;

                return exec1.getReturnType().toString().equals(exec2.getReturnType().toString());
            }

        };

        foreignTree.accept(foreignScanner, null);

        return location.stream().findFirst();
    }

    Optional<Location> locationForVariable(VariableElement elem) {
        var node = treesUtil.getTree(elem);

        return Optional.of(locationFor(uri, tree, node, elem.getSimpleName()::toString));
    }

    Location locationFor(String uri,
                         CompilationUnitTree tree,
                         Tree node,
                         Supplier<String> nameSupplier) {
        var sp = sourcePositions.getStartPosition(tree, node);
        var ep = sourcePositions.getEndPosition(tree, node);
        var lineMap = tree.getLineMap();
        try {
            var x = tree.getSourceFile()
                        .getCharContent(false)
                        .subSequence((int) sp, (int) ep)
                        .toString()
                        .indexOf(nameSupplier.get());

            return new Location(
                    uri, new Range(Util.encodePosition(lineMap, sp + x),
                                   Util.encodePosition(lineMap, ep)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    class Scanner extends TreeScanner<Element, Long> {
        List<CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>>
        futures = new ArrayList<>();

        @Override
        public Element reduce(Element r1, Element r2) {
            return Stream.of(r1, r2).filter(Objects::nonNull).findFirst().orElse(null);
        }

        @Override
        public Element visitBlock(BlockTree node, Long p) {
            if (definedBefore(node, p)) {
                return null;
            }

            return super.visitBlock(node, p);
        }

        @Override
        public Element visitClass(ClassTree node, Long p) {
            if (definedBefore(node, p)) {
                return null;
            }

            return super.visitClass(node, p);
        }

        @Override
        public Element visitIdentifier(IdentifierTree node, Long p) {
            if (!within(node, p)) {
                return null;
            }

            var path = treesUtil.getPath(tree, node);
            var elem = treesUtil.getElement(path);

            addIfClass(elem);
            addIfExecutable(elem);
            addIfVariable(elem);

            return elem;
        }

        @Override
        public Element visitLiteral(LiteralTree node, Long p) {
            // Ignore literals.
            return null;
        }

        @Override
        public Element visitMemberSelect(MemberSelectTree node, Long p) {
            if (!within(node, p)) {
                return null;
            }

            var expr = node.getExpression();
            var scanned = scan(expr, p);
            if (scanned != null) {
                return scanned;
            }

            var path = treesUtil.getPath(tree, node);
            var elem = treesUtil.getElement(path);

            addIfExecutable(elem);
            addIfVariable(elem);

            return elem;
        }

        @Override
        public Element visitMemberReference(MemberReferenceTree node, Long p) {
            if (!within(node, p)) {
                return null;
            }

            var expr = node.getQualifierExpression();
            var scanned = scan(expr, p);
            if (scanned != null) {
                return scanned;
            }

            var path = treesUtil.getPath(tree, node);
            var elem = treesUtil.getElement(path);

            addIfVariable(elem);

            return elem;
        }

        void addIfClass(Element elem) {
            if (elem instanceof TypeElement exec) {
                futures.add(CompletableFuture.supplyAsync(()
                            -> Either.forLeft(locationForType(exec).map(List::of).orElseGet(List::of))));
            }
        }

        void addIfExecutable(Element elem) {
            if (elem instanceof ExecutableElement exec) {
                futures.add(CompletableFuture.supplyAsync(()
                            -> Either.forLeft(locationForMethod(exec).map(List::of).orElseGet(List::of))));
            }
        }

        void addIfVariable(Element elem) {
            if (elem instanceof VariableElement ve) {
                futures.add(CompletableFuture.supplyAsync(()
                            -> Either.forLeft(locationForVariable(ve).map(List::of).orElseGet(List::of))));
            }
        }

        @Override
        public Element visitMethod(MethodTree node, Long p) {
            if (definedBefore(node, p)) {
                return null;
            }

            return super.visitMethod(node, p);
        }

        private boolean within(Tree node, long cursor) {
            var start = sourcePositions.getStartPosition(tree, node);
            var end = sourcePositions.getEndPosition(tree, node);

            var nopos = javax.tools.Diagnostic.NOPOS;
            if (start == nopos || end == nopos) {
                return false;
            }

            return cursor >= start && cursor <= end;
        }

        private boolean definedBefore(Tree node, long cursor) {
            var end = sourcePositions.getEndPosition(tree, node);
            return end < cursor;
        }

    }
}
