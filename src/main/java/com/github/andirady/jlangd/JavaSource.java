/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.github.andirady.jlangd;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 *
 * @author andirady
 */
public class JavaSource extends SimpleJavaFileObject {

    private String source;
    private long lastModified;
    private List<Integer> lineOffsets;

    public JavaSource(URI uri, String source) {
        super(uri, JavaFileObject.Kind.SOURCE);
        this.source = source;
        this.lastModified = System.currentTimeMillis();
        compulteLineOfsets();
    }

    private void compulteLineOfsets() {
        lineOffsets = new ArrayList<>();
        var counter = new AtomicInteger();
        var sepLength = System.lineSeparator().length();
        source.lines()
                .mapToInt(s -> s.length() + sepLength)
                .map(counter::getAndAdd)
                .forEach(lineOffsets::add);
        lastModified = System.currentTimeMillis();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return source;
    }

    @Override
    public long getLastModified() {
        return lastModified;
    }

    public int lineOffset(int line) {
        return lineOffsets.get(line);
    }

    public void patch(Range range, String text) {
        var startPos = range.getStart();
        var endPos = range.getEnd();
        var start = lineOffset(startPos.getLine()) + startPos.getCharacter();
        var end = lineOffset(Math.min(endPos.getLine(), lineOffsets.size() - 1)) + endPos.getCharacter();
        source = new StringBuilder(source).replace(start, end, text).toString();
        compulteLineOfsets();
    }
    
    public Position lspPosition(int offset) {
        var line = 0;
        var lineOffset = 0;
        for (var i = 0; i < lineOffsets.size(); i++) {
            var lo = lineOffsets.get(i);
            if (lo > offset) {
                break;
            }
            
            lineOffset = lo;
            line = i;
        }
        
        return new Position(line, offset - lineOffset);
    }

    public URI uri() {
        return uri;
    }
}

