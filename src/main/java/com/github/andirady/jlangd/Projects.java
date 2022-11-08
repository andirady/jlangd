/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.github.andirady.jlangd;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 *
 * @author andirady
 */
public final class Projects {

    private static final Projects INSTANCE = new Projects();

    public static Project forUri(String uri) {
        return forUri(URI.create(uri));
    }

    public static Project forUri(URI uri) {
        return forPath(Path.of(uri));
    }

    public static Project forPath(Path path) {
        var project = INSTANCE.projectInstances.stream()
                .filter(p -> path.startsWith(p.path()))
                .findFirst()
                .orElse(null);

        if (project == null) {
            var readers = ServiceLoader.load(ProjectReader.class);
            for (var reader : readers) {
                var p = reader.readProject(path);
                if (p != null) {
                    project = p;
                    break;
                }
            }

            if (project == null) {
                project = new DefaultProjectReader().readProject(path);
            }

            INSTANCE.projectInstances.add(project);
            return project;
        }
        
        return project;
    }

    public static void closeAll() {
        INSTANCE.projectInstances.stream().forEach(Project::close);
    }

    private final List<Project> projectInstances = new ArrayList<>();

    private Projects() {
    }
}
