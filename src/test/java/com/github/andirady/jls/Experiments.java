/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.github.andirady.javals;

import com.github.andirady.jlangd.FindNodeInTree;
import com.github.andirady.jlangd.JavaSource;
import com.github.andirady.jlangd.LoadTypes;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.Name;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 *
 * @author andirady
 */
public class Experiments {
    
    @Test
    public void testScanClasses() throws IOException {
        var t0 = System.currentTimeMillis();
        var javac = ToolProvider.getSystemJavaCompiler();
        var javaSource = new JavaSource(URI.create("file:///app/src/App.java"), "");
        var task = (JavacTask) javac.getTask(null, null, null, null, null, List.of(javaSource));
        task.parse();
        task.analyze();
        
        var types = new ArrayList<Name>();
        for (var moduleElement : task.getElements().getAllModuleElements()) {
            for (var packageElement : moduleElement.getEnclosedElements()) {
                for (var typeElement : packageElement.getEnclosedElements()) {
                    types.add(typeElement.getSimpleName());
                }
            }
        }
        
        System.out.println("Took " + (System.currentTimeMillis() - t0));
    }
    
    @Test
    public void testScanClassesByReadingFromClasspath() throws Exception {
        var t0 = System.currentTimeMillis();
        var cp = Files.lines(Path.of(System.getProperty("user.dir"), ".classpath.cache")).findFirst();
        
        var types = LoadTypes.streamTypeNames(cp.orElse(""), cannonName -> cannonName.contains("Compl")).toList();
        
        System.out.println("Loaded " + types.size() + " types in " + (System.currentTimeMillis() - t0) + " ms");
      
        assertTrue(types.contains("java.util.concurrent.CompletableFuture"));
        assertTrue(types.contains("org.eclipse.lsp4j.CompletionItem"));
        
        t0 = System.currentTimeMillis();
        cp = Files.lines(Path.of(System.getProperty("user.dir"), ".classpath.cache")).findFirst();
        
        types = LoadTypes.streamTypeNames(cp.orElse(""), cannonName -> cannonName.contains("Compl")).toList();
        
        System.out.println("Loaded " + types.size() + " types in " + (System.currentTimeMillis() - t0) + " ms");
    }

    @Test
    public void testCompletion() throws IOException {
        var src = """
                  public class App {
                      public static void main(String[] args) {
                      }
                  }
                  """;
        var javac = ToolProvider.getSystemJavaCompiler();
        var javaSource = new JavaSource(URI.create("file:///app/src/App.java"), src);
        var task = (JavacTask) javac.getTask(null, null, null, null, null, List.of(javaSource));
        var compilationUnitTrees = task.parse();
        var elements = task.analyze();
        elements.forEach(e -> {
            e.getEnclosedElements().forEach(ee -> System.out.println("e = " + e + ", ee = " + ee));
        });
        compilationUnitTrees.forEach(cu -> {
            if (cu.getSourceFile().getName().equals(javaSource.getName())) {
                var trees = Trees.instance(task);
                var sourcePositions = trees.getSourcePositions();
                var scanner = new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void data) {
                        System.out.println(node);
                        return super.visitIdentifier(node, data);
                    }

                    @Override
                    public Void visitErroneous(ErroneousTree node, Void data) {
                        var start = sourcePositions.getStartPosition(cu, node);
                        var end = sourcePositions.getEndPosition(cu, node);
                        getCurrentPath().getParentPath().forEach(t -> System.out.println("KIND: " + t.getKind()));
                        System.out.println(node + ", (" + start + ", " + end + "): [" + src.substring((int) start, (int) end) + "]");
                        var elem = trees.getElement(getCurrentPath());
                        System.out.println("elem = " + elem);
                        return super.visitErroneous(node, data);
                    }
                };
                scanner.scan(cu, null);
            }
        });
    }
    
    @Test
    public void testGetPathOfClass() throws Exception {
        var javac = ToolProvider.getSystemJavaCompiler();
        var fmgr = javac.getStandardFileManager(null, null, null);
        var src = new JavaSource(URI.create("string:///Hello.java"), """
                import java.util.ArrayList;

                class Hello {

                    public static void main(String[] args) {
                        var a = new ArrayList<String>();
                    }
                }
                """);
        var task = (JavacTask) javac.getTask(null, fmgr, null, null, null, List.of(src));
        task.parse();
        task.analyze();

        var javap = java.util.spi.ToolProvider.findFirst("javap").orElseThrow();
        var out = new java.io.StringWriter();
        var err = new java.io.StringWriter();
        javap.run(new java.io.PrintWriter(out), new java.io.PrintWriter(err), "-c", "java.util.ArrayList");

        System.out.println("===== Out =====");
        System.out.println(out);
        System.out.println("===== Error =====");
        System.out.println(err);
    }

    @Test
    public void testTest() throws IOException {
        var src = """
                  import static java.nio.file.Files.*;
                  import java.util.*;
                  
                  public class App {
                      public static void main(String[] args) {
                          var x = 1;
                      }
                  }
                  """;
        var javac = ToolProvider.getSystemJavaCompiler();
        var javaSource = new JavaSource(URI.create("file:///app/src/App.java"), src);
        var task = (JavacTask) javac.getTask(null, null, null, null, null, List.of(javaSource));
        var parsed = task.parse();
        var analyzed = task.analyze();
        var elements = task.getElements();
        var tree = parsed.iterator().next();
        var trees = Trees.instance(task);
        var scanner = new TreeScanner<Tree, Void>() {
            @Override
            public Tree reduce(Tree r1, Tree r2) {
                return Stream.of(r1, r2).filter(r -> r != null).findAny().orElse(null);
            }

            @Override
            public Tree visitVariable(VariableTree node, Void p) {
                return node.getInitializer();
            }
        };
        
        for (var i = 0; i < 5; i++) {
            var t0 = System.currentTimeMillis();
            var unitPath = new TreePath(tree);
            var node = tree.accept(scanner, null);
            var scope = trees.getScope(TreePath.getPath(unitPath, node));
            System.out.println("node = " + node + ", scope = " + scope);

            tree.getImports().stream()
                    .flatMap(imp -> {
                        if (!(imp.getQualifiedIdentifier() instanceof MemberSelectTree memberSelect)) {
                            return Stream.of();
                        }

                        var wildcardSelect = memberSelect.getIdentifier().contentEquals("*");
                        Predicate<Element> nonPrivateOrProtected = el -> el.getModifiers().stream().noneMatch(Set.of(Modifier.PRIVATE, Modifier.PROTECTED)::contains);
                        var expression = memberSelect.getExpression();

                        if (imp.isStatic()) {
                            var type = elements.getTypeElement(expression.toString());
                            var stream = type.getEnclosedElements().stream();
                            if (wildcardSelect) {
                                return stream.filter(nonPrivateOrProtected);
                            }

                            return stream.filter(el -> el.getSimpleName().contentEquals(memberSelect.getIdentifier()));
                        }

                        if (wildcardSelect) {
                            return elements.getPackageElement(expression.toString()).getEnclosedElements().stream()
                                    .filter(nonPrivateOrProtected);
                        }

                        return Stream.ofNullable(elements.getTypeElement(memberSelect.toString()));
                    })
                    .forEach(System.out::println);
            scope.getLocalElements().forEach(System.out::println);
            System.out.println("[" + i + "] Took " + (System.currentTimeMillis() - t0) + " ms.");
        }
    }
    
    @Test
    public void testSource() throws IOException {
        var src = """
                  import static java.nio.file.Files.*;
                  import java.nio.file.*;
                  import java.util.*;
                  
                  public class App {
                      public static void main(String[] args) {
                          var unique = Path.
                          if (args.length > 1) {
                              System.out.println("OK");
                          }
                      }
                  }
                  """;
        var javac = ToolProvider.getSystemJavaCompiler();
        var javaSource = new JavaSource(URI.create("file:///app/src/App.java"), src);
        var task = (JavacTask) javac.getTask(null, null, null, null, null, List.of(javaSource));
        var parsed = task.parse();
        var analyzed = task.analyze();
        //var elements = task.getElements();
        var tree = parsed.iterator().next();
        var trees = Trees.instance(task);
        var cursor = src.indexOf("unique");
        var finder = new FindNodeInTree(trees, tree);
        var node = finder.findAtCursor(cursor);
        
        assertNotNull(node);
        assertTrue(node instanceof VariableTree);
        System.out.println(node + ", " + node.getKind() + ", " + node.getClass() + ", " + cursor);
         
        cursor = src.indexOf("= Path.") + 6;
        node = finder.findAtCursor(cursor);
        System.out.println(node + ", " + node.getKind() + ", " + node.getClass() + ", " + cursor);
    }
}
