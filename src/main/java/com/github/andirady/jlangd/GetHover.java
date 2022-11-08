package com.github.andirady.jlangd;

import static java.util.stream.Collectors.*;

import java.util.concurrent.*;
import java.util.logging.*;
import javax.lang.model.element.*;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;

public class GetHover {

    private static final Logger LOG = Logger.getLogger(GetHover.class.getName());

    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var uri = params.getTextDocument().getUri();
            var project = Projects.forUri(uri);
            return doHover(project, uri, params.getPosition());
        });
    }

    // TODO move this to another class.
    private Hover doHover(Project project, String uri, Position position) {
        var tree = project.compilationUnitFor(uri);
        var task = project.previousTask().orElseThrow();
        var cursor = Util.decodePosition(tree.getLineMap(), position);
        var trees = Trees.instance(task);
        var finder = new FindNodeInTree(trees, tree);
        var node = finder.findAtCursor(cursor);

        LOG.fine(() -> "node = " + node + " " + node.getKind());
        if (node instanceof IdentifierTree || node instanceof MemberSelectTree) {
            var elem = trees.getElement(trees.getPath(tree, node));
            LOG.fine(() -> "elem = " + elem + " " + elem.getKind());

            if (elem.getKind() == ElementKind.LOCAL_VARIABLE) {
                return new Hover(Either.forLeft(elem.asType().toString()));
            } else if (elem.getKind().isField()) {
                var template = """
                               %2$s
                               
                               %1$s
                               """;
                return new Hover(new MarkupContent("markdown",
                                                   String.format(template,
                                                                 elem.getEnclosingElement(),
                                                                 elem.asType())));
            } else if (elem instanceof ExecutableElement ee) {
                var template = """
                               %2$s %3$s
                               """;
                return new Hover(new MarkupContent("markdown",
                                                   String.format(template,
                                                                 ee.getEnclosingElement(),
                                                                 ee.getReturnType(),
                                                                 ee)));
            } else if (elem instanceof TypeElement te) {
                var template = """
                               module %1$s
                               package %2$s
                        
                               %3$s %4$s %5$s%6$s
                               """;
                var modifiers = te.getModifiers().stream()
                        .filter(m -> switch (te.getKind()) {
                            case INTERFACE -> m != Modifier.ABSTRACT;
                            default -> true;
                        })
                        .map(Modifier::toString)
                        .collect(joining(" "));
                var typeParams = te.getTypeParameters().stream()
                        .map(Element::toString)
                        .collect(collectingAndThen(joining(","), s -> s.isEmpty() ? "" : ("<" + s + ">")));
                
                var pkg = te.getEnclosingElement();
                var text = String.format(template, pkg.getEnclosingElement(),
                                                   pkg,
                                                   modifiers,
                                                   te.getKind().toString().toLowerCase(),
                                                   te.getSimpleName(),
                                                   typeParams);
                return new Hover(new MarkupContent("markdown", text));
            } else {
                // TODO doc comment.
                // var refered = trees.getTree(elem);
                // var referedPath = trees.getPath(tree, refered);
                
                return new Hover(Either.forLeft(elem.asType().toString()));
            }
        } else if (node instanceof VariableTree vt) {
            return new Hover(Either.forLeft(vt.getType().toString()));
        }

        return null;
    }
    
}
