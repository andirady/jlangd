package com.github.andirady.javals;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.util.JavacTask;

class CompletionTest {
    
    @Test
    void should_suggest_types_when_non_static_import() throws IOException {
        var content = """
                import java.util.ArrayList;
                
                class A {
                    Arr###
                }
                """;
        var src = new StringJavaSourceWithCursor("/A.java", content);
        var javac = ToolProvider.getSystemJavaCompiler();
        var fmgr = javac.getStandardFileManager(null, null, null);
        var task = (JavacTask) javac.getTask(null, fmgr, null, null, null, List.of(src.source));
        var tree = task.parse().iterator().next();
    }
}
