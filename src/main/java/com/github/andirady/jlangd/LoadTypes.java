/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.github.andirady.jlangd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author andirady
 */
public final class LoadTypes {
    
    private static final Pattern ILLEGAL_NAME = Pattern.compile("\\$\\d+\\.class$");
    private static final Pattern MEMBER_SEP = Pattern.compile("/|\\$");
    private static final Pattern PATH_SEP = Pattern.compile(":");
    private static final String CLASS_EXT = ".class";
    private static final int CLASS_EXT_LENGTH = CLASS_EXT.length();
    
    private static final LoadTypes INSTANCE = new LoadTypes();
    
    public static Stream<String> streamTypeNames(String classpath, Predicate<String> filter) {
        return Stream.concat(
                INSTANCE.streamCachedTypeNames(classpath, filter),
                INSTANCE.jrtTypeNames(filter));
    }
    
    private List<String> jrtTypeNames;
    private final Map<String, List<String>> cache;
    
    private LoadTypes() {
        cache = new HashMap<>();
    }
    
    private Stream<String> streamCachedTypeNames(String classpath, Predicate<String> filter) {
        if (cache.containsKey(classpath)) {
            return cache.get(classpath).stream().filter(filter);
        }
        
        if (classpath.isEmpty()) {
            return Stream.empty();
        }
        
        var paths = PATH_SEP.splitAsStream(classpath).map(Path::of);
        return paths.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .flatMap(p -> {
                    try (
                        var fs = FileSystems.newFileSystem(p);
                        var stream = Files.walk(fs.getPath(""));
                    ) {
                        var st = stream.filter(Files::isRegularFile)
                                       .filter(this::ignore)
                                       .map(this::pathToTypeName);
                        
                        
                        var list = st.toList();
                        cache.put(classpath, list);
                        
                        st = list.stream();
                        
                        if (filter != null)
                            st = st.filter(filter);
                        
                        return st;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
    
    private Stream<String> jrtTypeNames(Predicate<String> filter) {
        if (jrtTypeNames != null) {
            return jrtTypeNames.stream().filter(filter);
        }
        
        var jrtRoot = Path.of(URI.create("jrt:/"));
        try (var stream = Files.walk(jrtRoot)) {
            var st = stream.parallel().filter(Files::isRegularFile)
                    .map(p -> p.subpath(2, p.getNameCount()))
                    .filter(this::ignore)
                    .map(this::pathToTypeName);
            
            jrtTypeNames = st.toList();
            
            st = jrtTypeNames.stream();
            
            if (filter != null)
                st = st.filter(filter);
                
            return st;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    private boolean ignore(Path p) {
        if (p.endsWith("module-info.class") || p.endsWith("package-info.class")) {
            return false;
        }
        
        var fn = p.getFileName().toString();
        return fn.endsWith(CLASS_EXT) && !ILLEGAL_NAME.matcher(fn).find();
    }
    
    private String pathToTypeName(Path path) {
        var name = MEMBER_SEP.splitAsStream(path.toString())
                .collect(Collectors.joining("."));
        return name.substring(0, name.length() - CLASS_EXT_LENGTH);
    }
}
