package com.github.andirady.jlangd;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

public class AntProjectReader implements ProjectReader {

    private static final Logger LOGGER = Logger.getLogger(AntProjectReader.class.getName());
    private static final String ANT_COMPILE_OUT = ".ant.compile.out";
    private static final String BUILD_XML = "build.xml";

    public static boolean isAntProject(Path path) {
        return Files.exists(path.resolve(BUILD_XML));
    }

    @Override
    public Project readProject(Path path) {
        var buildXml = path.resolve(BUILD_XML);
        if (!Files.exists(buildXml)) {
            return null;
        }

        var t0 = System.currentTimeMillis();
        var antCompileOut = path.resolve(ANT_COMPILE_OUT);
        if (!Files.exists(antCompileOut)) {
            LOGGER.severe(() -> String.format("Please generate `%1$s`. E.g. run `ant -d compile > %1$s`", ANT_COMPILE_OUT));
            throw new IllegalStateException();
        }

        try {
            var antCompileOutContent = Files.readString(antCompileOut);

            var start = antCompileOutContent.lines().takeWhile(line -> !line.trim().endsWith("[javac] Compilation arguments:")).count();
            var options = antCompileOutContent.lines().skip(start + 1)
                    .takeWhile(line -> !line.trim().endsWith("[javac]")).toList();

            var charset = Optional.<Charset>empty();
            var classpath = List.<Path>of();
            var srcDir = Optional.<Path>empty();
            var outDir = Optional.<Path>empty();

            var iter = options.iterator();
            var pathSep = System.getProperty("path.separator");
            while (iter.hasNext()) {
                var line = iter.next();
                var option = readEntry(line);
                switch (option) {
                    case "-classpath":
                        classpath = Arrays.stream(readEntry(iter.next()).split(pathSep))
                                .map(Path::of).toList();
                        break;
                    case "-d":
                        outDir = Optional.of(Path.of(readEntry(iter.next())));
                        break;
                    case "-encoding":
                        charset = Optional.of(readEntry(iter.next())).map(Charset::forName);
                        break;
                    case "-sourcepath":
                        // FIXME possible multiple paths.
                        srcDir = Arrays.stream(readEntry(iter.next()).split(pathSep))
                                .map(Path::of).findFirst();
                        break;
                    default:
                        LOGGER.fine(() -> "option = " + option);
                        break;
                }
            }

            var p = new Project(
                    path,
                    charset.orElse(Charset.defaultCharset()),
                    classpath,
                    classpath,
                    srcDir.orElseThrow(),
                    outDir.orElseThrow());

            LOGGER.fine(() -> "Project read in " + (System.currentTimeMillis() - t0) + " ms.");

            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    private String readEntry(String line) {
        var token = "[javac] ";
        var start = line.indexOf(token) + token.length();

        return line.substring(start + 1, line.length() - 1).trim(); // quotes excluded
    }
}
