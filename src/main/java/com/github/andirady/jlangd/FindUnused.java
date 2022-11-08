package com.github.andirady.jlangd;

import java.util.*;
import java.util.logging.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;

public class FindUnused {

    private static final Logger LOG = Logger.getLogger(FindUnused.class.getName());
    
    private JavacTask task;

    public FindUnused(JavacTask task) {
        this.task = task;
    }

    public List<? extends Tree> find(TaskEvent taskEvent) {
        var tree =taskEvent.getCompilationUnit();
        Objects.requireNonNull(tree);

        var type = taskEvent.getTypeElement();
        Objects.requireNonNull(type);

        var elementsUtil = task.getElements();
        var scanner = new Scanner(tree);
        tree.accept(scanner, Trees.instance(task));

        return scanner.usage
                      .entrySet()
                      .stream()
                      .filter(e -> e.getValue().count == 0)
                      .map(e -> e.getValue().originNode)
                      .distinct()
                      .toList();
    }

    class Counter {
        Tree originNode;
        int count;

        Counter(Tree originNode, int count) {
            this.originNode = originNode;
            this.count = count;
        }

        Counter increment() {
            count++;
            return this;
        }

        @Override
        public String toString() {
            return "{originNode=" + originNode + ", count=" + count + "}";
        }
    }

    class Scanner extends TreeScanner<Void, Trees> {

        private Map<Element, Counter> usage;
        CompilationUnitTree tree;

        Scanner(CompilationUnitTree tree) {
            this.tree = tree;
            this.usage = new HashMap<>();
        }

        @Override
        public Void visitImport(ImportTree node, Trees treesUtil) {
            var qid = node.getQualifiedIdentifier();
            if (!(qid instanceof MemberSelectTree select)) {
                return null;
            }

            var counter = new Counter(qid, 0);

            if (select.getIdentifier().contentEquals("*")) {
                var path = treesUtil.getPath(tree, select.getExpression());
                var elem = treesUtil.getElement(path);
                
                elem.getEnclosedElements().forEach(e -> usage.put(e, counter));
            } else if (node.isStatic()) {
                var path = treesUtil.getPath(tree, select.getExpression());
                var elem = treesUtil.getElement(path);

                elem.getEnclosedElements()
                    .stream()
                    .filter(e -> e.getSimpleName().contentEquals(select.getIdentifier()))
                    .forEach(e -> usage.put(e, counter));
            } else {
                var path = treesUtil.getPath(tree, qid);
                var elem = treesUtil.getElement(path);
                usage.put(elem, counter);
            }

            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Trees treesUtil) {
            var path = treesUtil.getPath(tree, node);
            var elem = treesUtil.getElement(path);
            usage.computeIfPresent(elem, (k, v) -> v.increment());

            return null;
        }
    }
}
