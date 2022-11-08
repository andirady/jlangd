package com.github.andirady.jlangd;

import org.eclipse.lsp4j.Position;

import com.sun.source.tree.LineMap;

public final class Util {

    static Position encodePosition(LineMap lineMap, long pos) {
        return new Position((int) lineMap.getLineNumber(pos) - 1,
                            (int) lineMap.getColumnNumber(pos) - 1);
    }
    
    static int decodePosition(LineMap lineMap, Position position) {
        return (int) lineMap.getPosition(position.getLine() + 1L, position.getCharacter() + 1L);
    }

}
