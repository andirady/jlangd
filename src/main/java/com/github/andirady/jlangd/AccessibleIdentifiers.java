package com.github.andirady.jlangd;

import com.sun.source.tree.*;
import java.util.*;
import java.util.stream.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;

public class AccessibleIdentifiers {

    private final Scope scope;
    private Stream<? extends Element> resultStream;

    public AccessibleIdentifiers(Scope scope) {
        this.scope = scope;
        resultStream = Stream.of();
    }

    public AccessibleIdentifiers includeFields() {
        resultStream = Stream.concat(resultStream, enclosingClassElements().map(ElementFilter::fieldsIn).flatMap(List::stream));
        return this;
    }

    public AccessibleIdentifiers includeMethods() {
        resultStream = Stream.concat(resultStream, enclosingClassElements().map(ElementFilter::methodsIn).flatMap(List::stream));
        return this;
    }

    private Stream<List<? extends Element>> enclosingClassElements() {
        var enclosingClass = scope.getEnclosingClass();
        if (enclosingClass == null) {
            return Stream.empty();
        }

        return Stream.of(enclosingClass.getEnclosedElements());
    }

    public AccessibleIdentifiers includeLocalElements() {
        var locals = scope.getLocalElements();
        resultStream = Stream.concat(resultStream, StreamSupport.stream(locals.spliterator(), false));
        return this;
    }

    public Stream<? extends Element> streamIdentifiers() {
        return resultStream;
    }
}
