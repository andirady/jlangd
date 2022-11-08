package com.github.andirady.jlangd;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import javax.lang.model.element.*;
import javax.tools.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;

public class RenameSymbol {

    private static final Logger LOG = Logger.getLogger(RenameSymbol.class.getName());

    private final Project project;
    private final String uri;

    public RenameSymbol(Project project, String uri) {
        this.project = project;
        this.uri = uri;
    }

    public WorkspaceEdit rename(Position position, String newName) {
        var tree = project.compilationUnitFor(uri);
        var task = project.previousTask().orElseThrow();
        var trees = Trees.instance(task);
        var finder = new FindNodeInTree(trees, tree);
        var lineMap = tree.getLineMap();
        var sourcePositions = trees.getSourcePositions();
        var cursor = Util.decodePosition(lineMap, position);

        var node = finder.findAtCursor(cursor);
        var elem = trees.getElement(trees.getPath(tree, node));

        if (elem == null) {
            LOG.fine(() -> "No element found for node " + node + " " + node.getKind());
            return new WorkspaceEdit();
        }
        
        return switch (elem.getKind()) {
            case CLASS -> renameClass((TypeElement) elem, newName);
            case FIELD, LOCAL_VARIABLE, PARAMETER -> renameVar(trees, sourcePositions, lineMap, tree, elem, newName);
            case METHOD -> renameMethod(trees, sourcePositions, lineMap, tree, elem, newName);
            default -> new WorkspaceEdit();
        };
    } 

    private WorkspaceEdit renameClass(TypeElement elem, String newName) {
        var we = new WorkspaceEdit();
        var path = Path.of(URI.create(uri));
        var oldName = path.getFileName().toString().replace(JavaFileObject.Kind.SOURCE.extension, "");

        if (elem.getNestingKind().isNested() || !oldName.equals(elem.getSimpleName().toString())) {
            return we;
        }
        var newPath = path.getParent().resolve(newName + JavaFileObject.Kind.SOURCE.extension);
        var renameFile = new RenameFile(path.toUri().toString(),
                                        newPath.toUri().toString(),
                                        new RenameFileOptions(false, true));
        we.setDocumentChanges(List.of(Either.forRight(renameFile)));
        LOG.fine(we::toString);

        return we;
    }

    private WorkspaceEdit renameMethod(
            Trees trees,
            SourcePositions sourcePositions,
            LineMap lineMap,
            CompilationUnitTree tree,
            Element elem,
            String newName) {
        if (!elem.getModifiers().contains(Modifier.PRIVATE)) {
            LOG.info(() -> "Only renaming private method is supported for now.");
            return new WorkspaceEdit();
        }

        var out = new ArrayList<TextEdit>();
        var scanner = new TreePathScanner<Void, Tree>() {

			@Override
			public Void visitIdentifier(IdentifierTree node, Tree p) {
				var el = trees.getElement(getCurrentPath());
				if (el == elem) {
					var range = p.getKind() == Tree.Kind.VARIABLE
                              ? rangeFor(uri, sourcePositions, lineMap, tree, node, node::getName)
                              : rangeFor(sourcePositions, lineMap, tree, node);
					var textEdit = new TextEdit(range, newName);
					out.add(textEdit);
				}

				return null;
			}

            @Override
            public Void visitMethod(MethodTree node, Tree p) {
                var el = trees.getElement(getCurrentPath());
				if (el != elem) {
                    return super.visitMethod(node, p);
                }

				var range = rangeFor(uri, sourcePositions, lineMap, tree, node, node::getName);
				var textEdit = new TextEdit(range, newName);
				out.add(textEdit);

                return null;
            }

        };

        scanner.scan(tree, tree);

        return new WorkspaceEdit(Map.of(uri, out));
    }

	private WorkspaceEdit renameVar(
            Trees trees,
            SourcePositions sourcePositions,
            LineMap lineMap,
            CompilationUnitTree tree,
            Element elem,
            String newName) {
		var out = new ArrayList<TextEdit>();
		var scanner = new TreePathScanner<Void, Void>() {

			@Override
			public Void visitIdentifier(IdentifierTree node, Void p) {
				var el = trees.getElement(getCurrentPath());
				if (el == elem) {
					var range = rangeFor(sourcePositions, lineMap, tree, node);
					var textEdit = new TextEdit(range, newName);
					out.add(textEdit);
				}

				return null;
			}

			@Override
			public Void visitVariable(VariableTree node, Void p) {
				var el = trees.getElement(getCurrentPath());
				if (el != elem) {
					return super.visitVariable(node, p);
				}

				var range = rangeFor(uri, sourcePositions, lineMap, tree, node, node::getName);
				var textEdit = new TextEdit(range, newName);
				out.add(textEdit);

				return null;
			}

		};

        scanner.scan(tree, null);

        return new WorkspaceEdit(Map.of(uri, out));
    }

    private Range rangeFor(SourcePositions sourcePositions, LineMap lineMap, CompilationUnitTree tree, Tree node) {
        var startPos = sourcePositions.getStartPosition(tree, node);
        var start = Util.encodePosition(lineMap, startPos);
        var endPos = sourcePositions.getEndPosition(tree, node);
        var end = Util.encodePosition(lineMap, endPos);

        return new Range(start, end);
    }

    private Range rangeFor(
            String uri,
            SourcePositions sourcePositions,
            LineMap lineMap,
            CompilationUnitTree tree,
            Tree node,
            Supplier<Name> extractName) {
        var name = extractName.get().toString();
        var startPos = sourcePositions.getStartPosition(tree, node);
        var endPos = sourcePositions.getEndPosition(tree, node);

        CharSequence charContent;
        try {
            charContent= project.getSource(uri).orElseThrow().getCharContent(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var nameOffset = charContent
                .subSequence((int) startPos, (int) endPos)
                .toString()
                .indexOf(name);

        var start = Util.encodePosition(lineMap, startPos + nameOffset);
        var end = Util.encodePosition(lineMap, startPos + nameOffset + name.length());

        return new Range(start, end);
    }
}
