package com.github.andirady.jlangd;

import com.github.andirady.jlangd.FindNodeInTree;
import com.github.andirady.jlangd.JavaSource;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

public class FindNodeInTreeTest {

    @ParameterizedTest
    @MethodSource
    public void testFindAtCursor(URI uri, String src, Tree.Kind expectedKind) throws Exception {
        var cursorToken = "<cursor>";
        var diags = new DiagnosticCollector();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, null, diags, null, null, List.of(new JavaSource(uri, src.replaceAll(cursorToken, ""))));
        var cus = task.parse();
        var trees = Trees.instance(task);
        var cursor = src.indexOf(cursorToken);
        var t0 = System.currentTimeMillis();
        var finder = new FindNodeInTree(trees, cus.iterator().next());
        var node = finder.findAtCursor(cursor);
        System.out.println("Took " + (System.currentTimeMillis() - t0) + " ms");
        assertNotNull(node);
        System.out.println("Found " + node.getKind() + ": " + node);
        assertSame(expectedKind, node.getKind());
    }

    public static Stream<Arguments> testFindAtCursor() {
        return Stream.of(
            Arguments.of(
                URI.create("string:///hello/Hello.java"), """
                package hello;

                import java.util.logging.*;

                public class Hello {
                    private static final Logger LOGGER = Logger.getLogger("");

                    public static void main(String[] args) {
                        var first = 1;
                        LOG<cursor>
                    }
                }
                """,
                Tree.Kind.ERRONEOUS
            ),
            Arguments.of(
                URI.create("string:///hello/Hello.java"), """
                package hello;

                import java.util.logging.*;

                public class Hello {
                    private static final Logger LOGGER = Logger.getLogger("");

                    public static void main(String[] args) {
                        var first = 1;
                        LOG<cursor>
                        var second = 2;
                    }
                }
                """,
                Tree.Kind.IDENTIFIER
            )

        );
    }

    @Test
    public void testFindInScope() throws Exception {
        var uri = URI.create("string:///hello/Hello.java");
        var src =  """
                   package hello;

                   import java.util.logging.*;

                   public class Hello {
                       private static final Logger LOGGER = Logger.getLogger("");

                       public static void main(String[] args) {
                           var first = 1;
                           var second = 2;
                           if (first == 1) {
                               var firstMsg = "Is first";
                           } else {
                               var secondMsg = "Is first";
                               LOG<cursor>
                           }
                       }
                   }
                   """;
        var cursorToken = "<cursor>";
        var diags = new DiagnosticCollector();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, null, diags, null, null, List.of(new JavaSource(uri, src.replaceAll(cursorToken, ""))));
        var cu = task.parse().iterator().next();
        var trees = Trees.instance(task);
        var cursor = src.indexOf(cursorToken);
        var t0 = System.currentTimeMillis();
        var finder = new FindNodeInTree(trees, cu);
        var node = finder.findAtCursor(cursor);
        assertNotNull(node);
        var startPath = trees.getPath(cu, node);
        assertNotNull(startPath);
        var scope = trees.getScope(startPath);
        System.out.println("Took " + (System.currentTimeMillis() - t0) + " ms");
        scope.getLocalElements().forEach(System.out::println);
    }
}
