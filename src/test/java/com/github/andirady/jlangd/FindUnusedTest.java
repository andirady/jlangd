package com.github.andirady.jlangd;

import com.github.andirady.jlangd.FindUnused;
import com.github.andirady.jlangd.JavaSource;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.util.*;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.*;

import com.sun.source.util.*;

class FindUnusedTest {

    @Test
    void should_find_when_explicit_import_is_unused() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import java.util.ArrayList;

                                    class A {
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                assertTrue(finder.find(event).stream().anyMatch(e -> e.toString().endsWith("ArrayList")));
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_not_find_when_explicit_import_is_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import java.util.ArrayList;

                                    class A {

                                        void f() {
                                            new ArrayList<String>();
                                        }
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                assertSame(0, finder.find(event).size());
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_not_find_when_any_member_of_wildcard_import_is_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import java.util.*;

                                    class A {
                                        ArrayList<String> x;
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                var result = finder.find(event);
                assertSame(0, result.size());
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_find_when_wildcard_static_import_is_not_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import static java.util.stream.Collectors.*;

                                    class A {
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                var result = finder.find(event);
                assertSame(1, result.size());
                assertTrue(result.stream().anyMatch(e -> e.toString().endsWith("java.util.stream.Collectors.*")));
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_find_when_explicit_static_import_is_unused() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import static java.util.stream.Collectors.joining;

                                    class A {
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                assertTrue(finder.find(event).stream().anyMatch(e -> e.toString().endsWith("java.util.stream.Collectors.joining")));
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_not_find_when_explicit_static_import_is_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import static java.util.stream.Collectors.joining;
                                    import java.util.List;

                                    class A {

                                        void f() {
                                            List.of("").stream().collect(joining());
                                        }
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                assertSame(0, finder.find(event).size());
            }
        });
        task.parse();
        task.analyze();
    }
    @Test
    void should_find_when_wildcard_import_is_not_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import java.util.*;

                                    class A {
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                var result = finder.find(event);
                assertSame(1, result.size());
                assertTrue(result.stream().anyMatch(e -> e.toString().endsWith("java.util.*")));
            }
        });
        task.parse();
        task.analyze();
    }

    @Test
    void should_not_find_when_any_member_of_wildcard_static_import_is_used() throws Exception {
        var source = new JavaSource(URI.create("string:///A.java"),
                                    """
                                    import static java.util.stream.Collectors.*;
                                    import java.util.List;

                                    class A {

                                        void f() {
                                            List.of("").stream().collect(joining());
                                        }
                                    }
                                    """);
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                           .getTask(null, null, null, null, null, List.of(source));
        var finder = new FindUnused(task);

        task.addTaskListener(new TaskListener() {

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE)
                    return;

                var result = finder.find(event);
                assertSame(0, result.size());
            }
        });
        task.parse();
        task.analyze();
    }
}
