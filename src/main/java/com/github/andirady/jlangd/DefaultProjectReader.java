package com.github.andirady.jlangd;

import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Optional;

public class DefaultProjectReader implements ProjectReader {

    @Override
    public Project readProject(Path path) {
        var sep = System.getProperty("path.separator");
        var cp = Optional.ofNullable(System.getenv("CLASSPATH"))
                .map(s -> s + sep + System.getProperty("user.dir"))
                .map(s -> s.split(sep))
                .stream()
                .flatMap(Arrays::stream)
                .map(Path::of)
                .toList();
        return new Project(path, Charset.defaultCharset(), cp, cp, path, null);
    }
}
