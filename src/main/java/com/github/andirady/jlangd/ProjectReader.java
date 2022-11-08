package com.github.andirady.jlangd;

import java.nio.file.Path;

public interface ProjectReader {

    Project readProject(Path path);
}
