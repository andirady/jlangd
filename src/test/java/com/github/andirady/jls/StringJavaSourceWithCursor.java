package com.github.andirady.javals;

import com.github.andirady.jlangd.JavaSource;
import java.net.URI;

public class StringJavaSourceWithCursor {
    private static final String CURSOR_TOKEN = "###";
    public JavaSource source;
    public int cursor;
    
    public StringJavaSourceWithCursor(String uri, String content) {
        this.source = new JavaSource(URI.create("string:/" + uri), content.replaceAll(CURSOR_TOKEN, ""));
        this.cursor = content.indexOf(CURSOR_TOKEN);
    }
}
