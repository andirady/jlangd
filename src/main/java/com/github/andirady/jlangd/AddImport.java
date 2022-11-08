package com.github.andirady.jlangd;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.logging.*;
import javax.lang.model.element.*;
import org.eclipse.lsp4j.*;

public final class AddImport {

    private static final Logger LOG = Logger.getLogger(AddImport.class.getName());

    private Project project;
    private String uri;
    private CompilationUnitTree tree;
    private Trees treesUtil;

    public AddImport(Project project, String uri) {
        this.project = project;
        this.uri = uri;
    }

    public AddImport(CompilationUnitTree tree, Trees treesUtil) {
        this.tree = tree;
        this.treesUtil = treesUtil;
    }

    public TextEdit addImport(TypeElement element) {
        var tree = project.compilationUnitFor(uri);
        var task = project.previousTask().orElseThrow();
        var treesUtil = Trees.instance(task);
        var sourcePositions = treesUtil.getSourcePositions();
        tree.getImports().forEach(e -> {
            var sp = sourcePositions.getStartPosition(tree, e);
            var ep = sourcePositions.getStartPosition(tree, e);
            LOG.fine(() -> e + " at " + sp + " - " + ep);
        });
        return null;
    }

    public TextEdit addImport(String fqcn) {
        var sourcePositions = treesUtil.getSourcePositions();
        var lineNum = insertAlphabetic(fqcn);
        var lineSep = System.lineSeparator();
        var end = lineSep.repeat(lineNum == 0 && tree.getImports().isEmpty() ? 2 : 1);
        var pos = new Position((int) lineNum, 0);
        var range = new Range(pos, pos);
        var textEdit = new TextEdit(range, "import " + fqcn + ";" + end);
        LOG.fine(textEdit::toString);
        return textEdit;
    }

    private boolean isAlphabetic() {
        var sp = treesUtil.getSourcePositions();
        var lineMap = tree.getLineMap();
        var imports = tree.getImports();
        for (var i = 1; i < imports.size(); i++) {
            var imp1 = imports.get(i - 1);
            var imp2 = imports.get(i);
            var line1 = Util.encodePosition(lineMap, sp.getStartPosition(tree, imp1)).getLine();
            var line2 = Util.encodePosition(lineMap, sp.getStartPosition(tree, imp2)).getLine();
            if (imp2.toString().compareTo(imp2.toString()) > 0 || (line2 - line1) > 1) {
                LOG.fine("isAlphabetic -> false");
                return false;
            }
        }

        LOG.fine("isAlphabetic -> true");
        return true;
    }

    private long insertAlphabetic(String fqcn) {
        var pkgEnd = packageEnd();
        var imports = tree.getImports();
        var sp = treesUtil.getSourcePositions();
        var lineMap = tree.getLineMap();
        return imports.stream()
                      .takeWhile(i -> i.getQualifiedIdentifier().toString().compareTo(fqcn) < 0)
                      .reduce((i1, i2) -> i2)
                      .map(i -> lineMap.getLineNumber(sp.getEndPosition(tree, i)))
                      .or(() -> imports.stream()
                                       .findFirst()
                                       .map(i -> lineMap.getLineNumber(sp.getEndPosition(tree, i)) - 1))
                      .filter(i -> i > 0)
                      .orElse(pkgEnd);
    }

    private String packageGroup(String id) {
        var first = id.split("\\.", 2)[0];
        return List.of("java", "javax", "org").contains(first) ? first : "";
    }

    private long packageEnd() {
        var pos = treesUtil.getSourcePositions().getEndPosition(tree, tree.getPackage());
        return pos < 0 ? 0 : pos;
    }
}
