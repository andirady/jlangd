package com.github.andirady.jlangd;

import java.util.*;
import java.util.logging.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

public class FindNodeInTree {
    private static final Logger LOG = Logger.getLogger(FindNodeInTree.class.getName());

    private final CompilationUnitTree tree;
    private SourcePositions sourcePositions;

    public FindNodeInTree(Trees trees, CompilationUnitTree tree) {
        this.tree = tree;
        this.sourcePositions = trees.getSourcePositions();
    }


    public Tree findAtCursor(int cursor) {
        var treeScanner = new TreeScanner<Tree, CompilationUnitTree>() {

            @Override
            public Tree reduce(Tree r1, Tree r2) {
                if (r1 == null && r2 != null) return r2;
                if (r2 == null && r1 != null) return r1;

                var start1 = sourcePositions.getStartPosition(tree, r1);
                var start2 = sourcePositions.getStartPosition(tree, r2);

                if (start1 > start2) {
                    return r1;
                }
                return r2;
            }

            @Override
            public Tree scan(Tree node, CompilationUnitTree tree) {
                var start = sourcePositions.getStartPosition(tree, node);
                var end = sourcePositions.getEndPosition(tree, node);

                if (cursor >= start && cursor <= end) {
                    // For debugging
                    //var l = tree.getLineMap().getLineNumber(start);
                    //var c = tree.getLineMap().getColumnNumber(start);
                    //LOGGER.fine(() -> String.format("[%d:%d %d] %s %s (%d,%d)", start, end, cursor, node.getKind(), node.toString().lines().findFirst().orElse(""), l, c));
                    // end for debugging

                    var result = super.scan(node, tree);
                    if (result == null) {
                        // Node has no child, so return. the node itself.
                        return node;
                    }

                    return result;
                }

                return null;
            }

            @Override
            public Tree visitMethod(MethodTree node, CompilationUnitTree tree) {
                var end = sourcePositions.getEndPosition(tree, node);
                if (end < cursor) { // not interested
                    return null;
                }

                return super.visitMethod(node, tree);
            }

            @Override
            public Tree visitClass(ClassTree node, CompilationUnitTree tree) {
                var end = sourcePositions.getEndPosition(tree, node);
                if (end < cursor) { // not interested
                    return null;
                }

                return super.visitClass(node, tree);
            }
        };
        return tree.accept(treeScanner, tree);
    }

    public Tree findInRange(int startPos, int endPos) {
        var out = new ArrayList<Tree>();
        var treeScanner = new TreeScanner<Tree, CompilationUnitTree>() {

            @Override
            public Tree scan(Tree node, CompilationUnitTree tree) {
                var currentStart = sourcePositions.getStartPosition(tree, node);
                var currentEnd = sourcePositions.getEndPosition(tree, node);
                LOG.fine(() -> String.format("[%d:%d, %d:%d] %s", startPos, currentStart, endPos, currentEnd, node));
                if (startPos >= currentStart && endPos >= currentEnd) {
                    // TODO
                }

                return null;
            }

        };

        tree.accept(treeScanner, tree);
        
        LOG.fine(() -> "out = " + out);
        return out.stream().filter(Objects::nonNull).findFirst().orElseThrow();
    }
}
