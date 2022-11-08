package com.github.andirady.jlangd;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.logging.*;

public class MavenProjectReader implements ProjectReader {

    private static final Logger LOG = Logger.getLogger(MavenProjectReader.class.getName());
    private static final String POM_XML = "pom.xml";
    private static final String CLASSPATH_CACHE = ".classpath.cache";

    public static boolean isMavenProject(Path path) {
        return Files.exists(path.resolve(POM_XML));
    }

    @Override
    public Project readProject(Path path) {
        var pomPath = path.resolve(POM_XML);
        if (!Files.exists(pomPath)) {
            return null;
        }

        var t0 = System.currentTimeMillis();
        var classpath = readClasspath(path, pomPath);

        var p = new Project(
                path,
                Charset.defaultCharset(),
                classpath,
                classpath,
                path.resolve("src").resolve("main").resolve("java"),
                path.resolve("target").resolve("classes"));

        LOG.fine(() -> "Project read in " + (System.currentTimeMillis() - t0) + " ms.");

        return p;
    }

    private List<Path> readClasspath(Path baseDir, Path pomPath) {
        var cache = baseDir.resolve(CLASSPATH_CACHE);
        if (Files.exists(cache) && lastModifiedOf(pomPath).compareTo(lastModifiedOf(cache)) < 0) {
            var cached = readClasspathFromCache(cache);
            if (cached.stream().allMatch(Files::exists)) {
                return cached;
            }
        }

        return invokeMavenBuildClasspath(baseDir, pomPath);
    }
    
    private FileTime lastModifiedOf(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> readClasspathFromCache(Path cachePath) {
        var sep = System.getProperty("path.separator");
        try {
            return Arrays.stream(Files.readString(cachePath).split(sep))
                    .map(Path::of)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> invokeMavenBuildClasspath(Path baseDir, Path pomPath) {
        LOG.info(() -> "Reading maven project...");
        try {
            var cachePath = baseDir.resolve(CLASSPATH_CACHE);
            Files.deleteIfExists(cachePath);
            var proc = new ProcessBuilder().command(
                    "mvn",
                    "--batch-mode",
                    "-f",
                    pomPath.toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cachePath).start();
            var ec = proc.waitFor();
            if (ec > 0) {
                throw new IllegalStateException("mvn exit " + ec);
            }

            var sep = System.getProperty("path.separator");
            return Arrays.stream((Files.exists(cachePath) ? Files.readString(cachePath): "").split(sep))
                    .map(Path::of)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

}
