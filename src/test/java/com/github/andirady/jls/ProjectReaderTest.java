package com.github.andirady.javals;

import com.github.andirady.jlangd.ProjectReader;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class ProjectReaderTest {

    Path base = Path.of("target", "testProject");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(base)) {
            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    throw e;
                }

            });
        }
    }

    @Test
    void testMaven() throws Exception {
        var pomXml = base.resolve("pom.xml");
        Files.createDirectory(base);
        Files.writeString(pomXml, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        
        var project = ServiceLoader.load(ProjectReader.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(reader -> reader.readProject(base))
                .findFirst()
                .orElseThrow();

        assertNotNull(project);
    }

}
