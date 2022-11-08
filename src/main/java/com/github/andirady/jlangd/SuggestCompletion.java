package com.github.andirady.jlangd;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.*;

public class SuggestCompletion extends TreeScanner<Stream<CompletionItem>, Integer> {

    private static final Logger LOG = Logger.getLogger(SuggestCompletion.class.getName());

    private Project project;
    private String uri;
    private String classpath;
    private JavacTask task;
    private Trees treesUtil;
    private Elements elementsUtil;
    private Types typesUtil;
    private CompilationUnitTree tree;
    private SourcePositions sourcePositions;

    private Scope scope;
    private int cursor;

    public SuggestCompletion(Project project, String uri) {
        this.project = project;
        this.uri = uri;
        var task = project.taskForUri(uri);
        this.task = task.task();
        this.classpath = project.classpathString();
        this.elementsUtil = task.task().getElements();
        this.typesUtil = task.task().getTypes();
        this.tree = task.findTreeForUri(uri).orElseThrow();
    }

    public Stream<CompletionItem> complete(Position pos) {
        treesUtil = Trees.instance(task);
        sourcePositions = treesUtil.getSourcePositions();

        var lineMap = tree.getLineMap();
        cursor = (int) lineMap.getPosition(pos.getLine() + 1L, pos.getCharacter() + 1L);

        var node = findNode(cursor);

        if (node == null)
            return Stream.empty();

        var path = treesUtil.getPath(tree, node);

        if (path == null) {
            LOG.warning(() -> "No path found for node " + node + " of kind " + node.getKind());
            return Stream.empty();
        }

        this.scope = treesUtil.getScope(path);

        return scan(node, cursor);
    }

    @Override
    public Stream<CompletionItem> reduce(Stream<CompletionItem> r1, Stream<CompletionItem> r2) {
        return Stream.of(r1, r2) .filter(Objects::nonNull)
                .flatMap(s -> s);
    }

    // Will only be visited for '==' or '!=' operator
    // and the cursor is at the right operand.
    @Override
    public Stream<CompletionItem> visitBinary(BinaryTree node, Integer cursor) {
        var leftPath = treesUtil.getPath(tree, node.getLeftOperand());
        if (leftPath == null) return Stream.empty();

        var leftElem = treesUtil.getElement(leftPath);
        var enumElem = asTypeElement(leftElem);
        if (enumElem.getKind() != ElementKind.ENUM)
            return scan(node.getRightOperand(), cursor);

        var right = node.getRightOperand();
        if (right instanceof MemberSelectTree select)
            return visitMemberSelect(select, cursor);

        var imported = streamStaticImports()
            .filter(e -> e.getEnclosedElements() == enumElem)
            .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
            .toList();

        var rightStart = sourcePositions.getStartPosition(tree, right);
        var rightEnd = sourcePositions.getEndPosition(tree, right);
        var lineMap = tree.getLineMap();
        var start = Util.encodePosition(lineMap, rightStart);
        var end = Util.encodePosition(lineMap, rightEnd);

        return enumElem.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
            .map(e -> {
                var label = !imported.contains(e)
                          ? e.getEnclosingElement().getSimpleName() + "." + e
                          : e.toString();
                var item = new CompletionItem(label);
                item.setKind(CompletionItemKind.EnumMember);

                var te = new TextEdit(new Range(start, end), label);
                item.setTextEdit(Either.forLeft(te));

                return item;
            });
    }

    @Override
    public Stream<CompletionItem> visitCase(CaseTree node, Integer cursor) {
        var path = treesUtil.getPath(tree, node);
        var isCaseStatement = node.getCaseKind() == CaseTree.CaseKind.STATEMENT;
        if (path == null) return Stream.of();
        
        if (isCaseStatement && 
            node.getExpressions()
                .stream()
                .map(Tree::getKind)
                .filter(k -> k == Tree.Kind.IDENTIFIER || k == Tree.Kind.STRING_LITERAL)
                .count() > 0) {
            return Stream.of();
        }

        if (!(path.getParentPath().getLeaf() instanceof SwitchTree switchNode)) {
            LOG.warning(() -> "Case " + node + " is not a child of a switch at " + cursor);
            return Stream.of();
        }

        var elem = treesUtil.getElement(treesUtil.getPath(tree, switchNode.getExpression()));
        TypeElement type = null;
        // FIXME Can this use `asTypeElement()`?
        if (elem instanceof ExecutableElement exe
                && typesUtil.asElement(exe.getReturnType()) instanceof TypeElement te) {
            type = te;
        } else if (elem instanceof VariableElement variable)
            type = (TypeElement) typesUtil.asElement(variable.asType());

        if (type == null || type.getKind() != ElementKind.ENUM) {
            LOG.fine(() -> "Can't resolve type or type kind is not enum");
            return Stream.of();
        }

        return elementsUtil.getAllMembers(type)
                .stream()
                .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                .map(this::convert);
    }

    @Override
    public Stream<CompletionItem> visitClass(ClassTree node, Integer cursor) {
        var firstCurly = node.toString().indexOf('{');
        if (cursor > firstCurly) {
            // I guest this could be a comment. :D
            return Stream.empty();
        }

        var extendsClause = node.getExtendsClause();
        if (extendsClause == null)
            return super.visitClass(node, cursor);

        LOG.fine(() -> "extends: " + extendsClause);
        return super.visitClass(node, cursor);
    }

    @Override
    public Stream<CompletionItem> visitErroneous(ErroneousTree node, Integer p) {
        if (scope.getEnclosingClass() == null) { // toplevel
            var out = new ArrayList<String>();
            out.add("import");

            if (tree.getPackageName() == null)
                out.add("package");

            return out.stream()
                      .map(label -> {
                          var ci = new CompletionItem(label);
                          ci.setKind(CompletionItemKind.Keyword);
                          return ci;
                      });
        }

        return node.getErrorTrees().stream().flatMap(unresolved -> {
            if (unresolved instanceof IdentifierTree identifer) {
                return visitIdentifier(identifer, p);
            } else if (unresolved instanceof MemberSelectTree memberSelect) {
                return visitMemberSelect(memberSelect, p);
            } else if (unresolved instanceof NewClassTree newClass) {
                return visitNewClass(newClass, p);
            }

            LOG.warning(() -> "Can't resolve `" + unresolved + "`. class=" + unresolved.getClass() + " kind=" + unresolved.getKind());
            return Stream.empty();
        });
    }

    @Override
    public Stream<CompletionItem> visitIdentifier(IdentifierTree node, Integer p) {
        LOG.fine(() -> "node: " + node + " " + node.getKind());

        var name = node.getName().toString();
        var localScope = Stream.of(
                    streamLocalElements(),
                    Stream.ofNullable(scope.getEnclosingClass())
                          .filter(Objects::nonNull)
                          .map(elementsUtil::getAllMembers)
                          .flatMap(List::stream),
                    streamStaticImports()
                )
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .filter(el -> el.getSimpleName().toString().startsWith(name))
                .toList();

        if (!localScope.isEmpty()) {
            return localScope.stream()
                             .map(e -> (e instanceof ExecutableElement exe)
                                     ? convertExecutable(exe, node.getName()) : convert(e));
        }

        return LoadTypes.streamTypeNames(classpath, cannonName -> {
                            var simpleName = cannonName.substring(cannonName.lastIndexOf('.') + 1);
                            return simpleName.contains(name);
                        })
                        .map(this::getTypeElement)
                        .filter(Objects::nonNull)
                        .filter(type -> treesUtil.isAccessible(scope, type))
                        .map(e -> (e instanceof ExecutableElement exe)
                                ? convertExecutable(exe, node.getName()) : convert(e));
    }

    @Override
    public Stream<CompletionItem> visitImport(ImportTree node, Integer cursor) {
        var id = node.getQualifiedIdentifier();

        if (!(id instanceof MemberSelectTree select))
            return Stream.empty();

        return selectPackageMember(select);
    }

    private Stream<CompletionItem> selectPackageMember(MemberSelectTree select) {
        var prefix = select.getIdentifier().contentEquals("<error>")
                   ? select.getExpression().toString() : select.toString();
        synchronized (elementsUtil) {
             return elementsUtil.getAllModuleElements().stream()
                                .map(ModuleElement::getEnclosedElements)
                                .flatMap(List::stream)
                                .filter(p -> p.toString().startsWith(prefix))
                                .map(PackageElement.class::cast)
                                .flatMap(p -> {
                                    return p.getQualifiedName().contentEquals(prefix)
                                         ? p.getEnclosedElements().stream()
                                         : Stream.of(p);
                                })
                                .map(p -> {
                                    var name = p.toString();
                                    var label = name.equals(prefix)
                                              ? name
                                              : name.substring(prefix.length() + 1, name.length());
                                    var item = new CompletionItem(label);
                                    item.setKind(switch (p.getKind()) {
                                        case CLASS, RECORD -> CompletionItemKind.Class;
                                        case ENUM -> CompletionItemKind.Enum;
                                        case FIELD -> CompletionItemKind.Field;
                                        case INTERFACE, ANNOTATION_TYPE -> CompletionItemKind.Interface;
                                        case PACKAGE -> CompletionItemKind.Module;
                                        default -> throw new IllegalArgumentException("Invalid element kind: " + p.getKind());
                                    });

                                    return item;
                                });
        }
    }

    @Override
    public Stream<CompletionItem> visitMemberReference(MemberReferenceTree node, Integer cursor) {
        //LOG.fine(node::toString);
        var expression = node.getQualifierExpression();
        var end = sourcePositions.getEndPosition(tree, node);
        if ((end + 1) == cursor) // inclomplete member select.
            return Stream.of();

        var path = treesUtil.getPath(tree, node);

        return selectMember(path,
                            expression,
                            e -> e.getKind() == ElementKind.METHOD
                              && e instanceof ExecutableElement exe
                              && !(exe.getReturnType() instanceof NoType),
                            e -> {
                                var item = new CompletionItem(e.getSimpleName().toString());
                                item.setDetail(((ExecutableElement) e).getReceiverType() + " " + e.toString());
                                item.setKind(CompletionItemKind.Method);

                                return item;
                            });
    }

    @Override
    public Stream<CompletionItem> visitMemberSelect(MemberSelectTree node, Integer cursor) {
        var expr = node.getExpression();
        var id = node.getIdentifier();
        var end = sourcePositions.getEndPosition(tree, expr);
        var prefix = (id.contentEquals("<error>") || (end + id.length()) >= cursor)
                   ? elementsUtil.getName("") : id;

        var path = treesUtil.getPath(tree, node);

        return selectMember(path, expr, e -> true, e -> e instanceof ExecutableElement exe
                                                      ? convertExecutable(exe, prefix)
                                                      : convert(e));
    }

    @Override
    public Stream<CompletionItem> visitPackage(PackageTree node, Integer cursor) {
        LOG.fine(node::toString);
        return Stream.generate(() -> project.getPackageForUri(uri))
                     .limit(1)
                     .map(p -> new CompletionItem(p));
    }

    private Stream<CompletionItem> selectMember(TreePath currentPath,
                                                ExpressionTree expression,
                                                Predicate<Element> filter,
                                                Function<Element, CompletionItem> converter) {
        var elem = currentPath == null
                 ? new ResolveElement().scan(expression, null)
                 : treesUtil.getElement(treesUtil.getPath(tree, expression));

        if (elem == null) {
            LOG.fine(() -> "Can't find element for " + expression);
            return Stream.empty();
        }

        if (elem instanceof ExecutableElement e) {
            var returnType = (TypeElement) typesUtil.asElement(e.getReturnType());

            return nonStaticAccessMembers(returnType).filter(filter).map(converter);
        } else if (elem instanceof PackageElement pkg) {
            var pkgQName = pkg.getQualifiedName().toString();
            var pkgElem = Optional.ofNullable(elementsUtil.getPackageElement(pkgQName));

            if (pkgElem.isEmpty()) {
                var possiblyLocal = Stream.concat(
                        streamLocalElements(),
                        Stream.ofNullable(scope.getEnclosingClass())
                              .map(elementsUtil::getAllMembers)
                              .flatMap(List::stream)
                    )
                    .filter(el -> el.getSimpleName().contentEquals(pkgQName)).findFirst();

                if (possiblyLocal.isPresent()) {
                    return nonStaticAccessMembers(possiblyLocal.map(Element::asType).orElseThrow())
                            .filter(filter).map(converter);
                }
            }

            // starts with lowercase
            var firstChar = pkg.getSimpleName().toString().substring(0, 1);
            if (firstChar.equals(firstChar.toLowerCase())) {
                var elemsUnderPkg = pkgElem.stream()
                        .map(PackageElement::getEnclosedElements)
                        .flatMap(List::stream);
                var pkgs = elementsUtil.getAllModuleElements()
                        .stream()
                        .flatMap(m -> m.getEnclosedElements().stream())
                        .map(PackageElement.class::cast)
                        .filter(p -> p.getQualifiedName().toString().startsWith(pkgQName));

                return Stream.concat(elemsUnderPkg, pkgs).filter(filter).map(converter);
            }

            return streamImportedTypes().filter(el -> el.getSimpleName().contentEquals(pkgQName))
                                        .findFirst()
                                        .stream()
                                        .flatMap(this::nonStaticAccessMembers)
                                        .filter(filter)
                                        .map(converter);
        } else if (elem instanceof TypeElement type) {
            LOG.fine(() -> elem + " is type");
            return elementsUtil.getAllMembers(type)
                               .stream()
                               .filter(el -> el.getModifiers().contains(Modifier.STATIC))
                               .filter(el -> declared(el))
                               .filter(el -> notConstructor(el))
                               .filter(el -> memberAccessible(el, type)).filter(filter).map(converter);
        } else if (elem instanceof VariableElement) {
            var typeMirror = elem.asType();
            var typeKind = typeMirror.getKind();
            return nonStaticAccessMembers(typeKind == TypeKind.TYPEVAR
                    ? typesUtil.erasure(typeMirror) : typeMirror).filter(filter).map(converter);
        }

        return Stream.empty();
    }

    @Override
    public Stream<CompletionItem> visitNewClass(NewClassTree node, Integer cursor) {
        var expr = node.getIdentifier();

        if (expr instanceof IdentifierTree id) {
            var types = ElementFilter.typesIn(elementsUtil.getAllMembers(scope.getEnclosingClass()));
            var prefix = id.getName();
            return Stream.concat(types.stream(), streamImportedTypes())
                         .filter(t -> t.getSimpleName().toString().startsWith(prefix.toString()))
                         .map(this::convert);
        } else if (expr instanceof MemberSelectTree memberSelect) {
            var selectExpr = memberSelect.getExpression().toString();
            var pkg = elementsUtil.getPackageElement(selectExpr);
            if (pkg != null) {
                return pkg.getEnclosedElements().stream()
                          .map(this::convert);
            }

            var selectId = memberSelect.getIdentifier();
            var prefix = selectId.contentEquals("<error>") ? elementsUtil.getName("") : selectId;

            var type = getTypeElement(selectExpr);
            if (type != null) {
                return ElementFilter.typesIn(type.getEnclosedElements())
                                    .stream()
                                    .flatMap(t -> ElementFilter.constructorsIn(t.getEnclosedElements()).stream())
                                    .map(c -> convertExecutable(c, prefix));
            }

            var types = ElementFilter.typesIn(elementsUtil.getAllMembers(scope.getEnclosingClass()));
            return Stream.concat(types.stream(), streamImportedTypes())
                         .filter(p -> p.getSimpleName().toString().equals(selectExpr))
                         .findFirst()
                         .stream()
                         .flatMap(t -> ElementFilter.typesIn(t.getEnclosedElements()).stream())
                         .filter(t -> treesUtil.isAccessible(scope, t))
                         .flatMap(t -> ElementFilter.constructorsIn(t.getEnclosedElements()).stream())
                         .map(c -> convertExecutable(c, prefix));
        }

        return null;
    }

    private TypeElement asTypeElement(Element elem) {
        if (elem instanceof TypeElement t) {
            return t;
        } else if (elem instanceof ExecutableElement e) {
            var rt = e.getReturnType();
            return (TypeElement) typesUtil.asElement(rt);
        } else if (elem instanceof VariableElement variable) {
            var typeMirror = variable.asType();
            var kind = typeMirror.getKind();
            if (kind.isPrimitive() || typeMirror instanceof NoType || kind == TypeKind.ARRAY)
                return null;

            return (TypeElement) typesUtil.asElement(variable.asType());
        }

        throw new IllegalArgumentException("Can't convert " + elem + " as TypeElement. It's kind is " + elem.getKind());
    }

    private TypeElement getTypeElement(CharSequence canonName) {
        return project.getTypeElement(elementsUtil, canonName);
    }

    private Stream<? extends Element> nonStaticAccessMembers(TypeMirror typeMirror) {
        if (typeMirror.getKind().isPrimitive() || typeMirror instanceof NoType
                || typeMirror.getKind() == TypeKind.ARRAY)
            return Stream.empty();

        var type = (TypeElement) typesUtil.asElement(typeMirror);
        return nonStaticAccessMembers(type);
    }

    private Stream<? extends Element> nonStaticAccessMembers(TypeElement type) {
        return Stream.ofNullable(type)
                .map(elementsUtil::getAllMembers)
                .flatMap(List::stream)
                .filter(this::notClassOrInterface)
                .filter(this::notStatic)
                .filter(this::notConstructor)
                .filter(this::declared)
                .filter(e -> memberAccessible(e, type));
    }

    private boolean declared(Element el) {
        return elementsUtil.getOrigin(el).isDeclared();
    }

    private boolean memberAccessible(Element member, TypeElement type) {
        return treesUtil.isAccessible(scope, member, typesUtil.getDeclaredType(type));
    }

    private boolean notClassOrInterface(Element el) {
        var kind = el.getKind();
        return !(kind.isClass() || kind.isInterface());
    }

    private boolean notConstructor(Element el) {
        return el.getKind() != ElementKind.CONSTRUCTOR;
    }

    private boolean nonPrivateOrProtected(Element el) {
        return el.getModifiers()
                 .stream()
                 .noneMatch(Set.of(Modifier.PRIVATE, Modifier.PROTECTED)::contains);
    }

    private boolean notStatic(Element el) {
        return !el.getModifiers().contains(Modifier.STATIC)
                && el.getKind() != ElementKind.STATIC_INIT;
    }

    private Stream<TypeElement> streamImportedTypes() {
        return tree.getImports().stream()
                   .map(ImportTree::getQualifiedIdentifier)
                   .filter(id -> id instanceof MemberSelectTree)
                   .map(MemberSelectTree.class::cast)
                   .flatMap(memberSelect -> {
                       var wildcardSelect = memberSelect.getIdentifier().contentEquals("*");
                       var expression = memberSelect.getExpression();

                       if (wildcardSelect) {
                           var pkg = elementsUtil.getPackageElement(expression.toString());
                           if (pkg == null)
                               return Stream.empty();

                           return pkg.getEnclosedElements().stream()
                                     .filter(this::nonPrivateOrProtected)
                                     .map(TypeElement.class::cast);
                       }

                       var type = getTypeElement(memberSelect.toString());
                       return Stream.ofNullable(type);
                   })
                   .filter(Objects::nonNull);
    }

    private Stream<Element> streamStaticImports() {
        return tree.getImports()
                   .stream()
                   .filter(ImportTree::isStatic)
                   .map(ImportTree::getQualifiedIdentifier)
                   .filter(id -> id instanceof MemberSelectTree)
                   .map(MemberSelectTree.class::cast)
                   .flatMap(memberSelect -> {
                       var wildcardSelect = memberSelect.getIdentifier().contentEquals("*");
                       var expression = memberSelect.getExpression();

                       if (wildcardSelect) {
                           var type = getTypeElement(expression.toString());
                           LOG.fine(() -> "type not found for " + expression);
                           if (type == null)
                               return Stream.empty();

                           return type.getEnclosedElements().stream().filter(this::nonPrivateOrProtected);
                       }

                       return Stream.ofNullable(treesUtil.getPath(tree, memberSelect))
                                    .map(treesUtil::getElement);
                   });
    }

    private Stream<? extends Element> streamLocalElements() {
        return StreamSupport.stream(scope.getLocalElements().spliterator(), false);
    }

    private CompletionItem convert(Element elem) {
        if (elem instanceof PackageElement e) {
            var item = new CompletionItem(e.getSimpleName().toString());
            item.setKind(CompletionItemKind.Module);
            return item;
        } else if (elem instanceof TypeElement type) {
            return convert(type);
        } else if (elem instanceof VariableElement variable) {
            var kind = switch (variable.getKind()) {
                case BINDING_VARIABLE, EXCEPTION_PARAMETER, LOCAL_VARIABLE -> CompletionItemKind.Variable;
                case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
                case FIELD -> CompletionItemKind.Field;
                default -> null;
            };

            var item = new CompletionItem(variable.getSimpleName().toString());
            item.setKind(kind);

            return item;
        }

        return new CompletionItem(elem.getSimpleName().toString());
    }

    /**
     */
    private CompletionItem convertExecutable(ExecutableElement elem, Name prefix) {
        var label = elem.getKind() == ElementKind.METHOD
                  ? elem.getSimpleName().toString()
                  : elem.getEnclosingElement().getSimpleName().toString(); // FIXME constructor
        var item = new CompletionItem(label);
        var newText = elem.getParameters()
            .stream()
            .map(param -> streamLocalElements()
                    .map(localElem -> {
                        // FIXME this does not handle generics.
                        if (localElem instanceof VariableElement ve) {
                            if (typesUtil.isAssignable(ve.asType(), param.asType()))
                                return ve.getSimpleName();
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .map(Name::toString)
                    .sorted(Comparator.comparingInt(name -> param.getSimpleName().toString().equals(name) ? 0 : 1))
                    .findFirst()
                    .orElseGet(() -> switch (param.asType().getKind()) {
                        case BOOLEAN -> "false";
                        case BYTE, SHORT, INT -> "0";
                        case LONG -> "0l";
                        case DOUBLE -> "0.0";
                        case FLOAT -> "0.0f";
                            default -> {
                                if (param.asType().toString().equals(String.class.getName())) {
                                    yield "\"\"";
                                }
                                yield "null";
                            }
                    })
            )
            .collect(Collectors.joining(", "));

        var lineMap = tree.getLineMap();
        var cursorPos = Util.encodePosition(lineMap, cursor - prefix.length());
        var textEdit = new TextEdit(new Range(cursorPos, cursorPos), elem.getSimpleName() + "(" + newText + ")");
        item.setTextEdit(Either.forLeft(textEdit));

        //var detail = String.format("%s%s%s %s(%s)",
        //                           elem.getModifiers().stream().anyMatch(Modifier.STATIC::equals) ? "static " : "",
        //                           elem.getTypeParameters().isEmpty()
        //                                   ? ""
        //                                   : elem.getTypeParameters()
        //                                         .stream()
        //                                         .map(Objects::toString)
        //                                         .collect(Collectors.joining(", ", "<", "> ")),
        //                           elem.getReturnType(),
        //                           elem.getSimpleName(),
        //                           elem.getParameters()
        //                               .stream()
        //                               .map(e -> e.asType() + " " + e.getSimpleName())
        //                               .collect(Collectors.joining(", ")));

        item.setDetail(elem.getReturnType() + " " + elem);
        item.setKind(CompletionItemKind.Method);
        if (elementsUtil.isDeprecated(elem))
            item.setTags(List.of(CompletionItemTag.Deprecated));

        var docTrees = DocTrees.instance(task);
        var comment = docTrees.getDocCommentTree(elem);
        if (comment != null) {
            item.setDocumentation(comment.toString());
        }

        return item;
    }

    private CompletionItem convert(TypeElement type) {
        var item = new CompletionItem(type.getSimpleName().toString());
        item.setKind(switch (type.getKind()) {
            case ANNOTATION_TYPE, INTERFACE -> CompletionItemKind.Interface;
            case CLASS, RECORD -> CompletionItemKind.Class;
            case ENUM -> CompletionItemKind.Enum;
            default -> null;
        });

        var pkgOrType = type.getEnclosingElement();
        if (pkgOrType != null) {
            item.setDetail(pkgOrType.toString());
        }

        item.setData(uri);
        return item;
    }
    
    private Tree findNode(int cursor) {

        var finder = new TreeScanner<Tree, Void>() {

            @Override
            public Tree reduce(Tree r1, Tree r2) {
                if (r1 == null && r2 == null) return null;
                if (r1 == null) return r2;
                if (r2 == null) return r1;

                // returns the innermost node.
                return sourcePositions.getStartPosition(tree, r2) > sourcePositions.getStartPosition(tree, r1)
                        ? r2 : r1;
            }

            @Override
            public Tree scan(Tree node, Void p) {
                if (within(node, cursor)) {
                    LOG.fine(() -> node.getKind() + ", " + node.getClass());
                    return super.scan(node, p);
                }

                return null;
            }

            @Override
            public Tree visitBinary(BinaryTree node, Void p) {
                if (within(node, cursor)) {
                    var kind = node.getKind();
                    // Might be enum
                    if ((kind == Tree.Kind.EQUAL_TO || kind == Tree.Kind.NOT_EQUAL_TO)
                            && within(node.getRightOperand(), cursor)) {
                        return node;
                    }

                    return super.visitBinary(node, p);
                }

                return null;
            }

            @Override
            public Tree visitBlock(BlockTree node, Void p) {
                if (definedBefore(node, cursor)) {
                    return null; // not interested
                }

                return super.visitBlock(node, p);
            }

            @Override
            public Tree visitCase(CaseTree node, Void p) {
                if (within(node, cursor)) {
                    return node;
                }

                return null;
            }

            @Override
            public Tree visitClass(ClassTree node, Void p) {
                if (definedBefore(node, cursor)) {
                    return null; // not interested
                }

                return super.visitClass(node, p);
            }

            @Override
            public Tree visitErroneous(ErroneousTree node, Void p) {
                if (within(node, cursor)) {
                    return node;
                }

                return null;
            }

            @Override
            public Tree visitIdentifier(IdentifierTree node, Void p) {
                if (within(node, cursor)) {
                    return node;
                }

                return null;
            }

            @Override
            public Tree visitImport(ImportTree node, Void p) {
                if (within(node, cursor)) {
                    return node;
                }

                return null;
            }

            @Override
            public Tree visitLiteral(LiteralTree node, Void p) {
                // Ignore literals.
                return null;
            }

            @Override
            public Tree visitMemberReference(MemberReferenceTree node, Void p) {
                if (within(node, cursor)) {
                    return node;
                }

                return null;
            }

            @Override
            public Tree visitMemberSelect(MemberSelectTree node, Void p) {
                if (within(node, cursor)) {
                    var expr = scan(node.getExpression(), p);
                    // FIXME this does not properly resolve to the correct member select.
                    // e.g.:
                    //   foo.bar.baz
                    //      ^ cursor is here
                    // will not resolve to ``foo.``
                    if (expr != null) {
                        return expr;
                    }

                    return node;
                }

                return null;
            }

            @Override
            public Tree visitMethod(MethodTree node, Void p) {
                if (definedBefore(node, cursor)) {
                    return null; // not interested
                }

                return super.visitMethod(node, p);
            }

            @Override
            public Tree visitNewClass(NewClassTree node, Void p) {
                if (within(node, cursor)) {
                    var id = scan(node.getIdentifier(), p);
                    if (id != null)
                        return id;

                    var arg = scan(node.getArguments(), p);
                    if (arg != null)
                        return arg;

                    var body = node.getClassBody();
                    if (body != null) {
                        var n = scan(body, p);
                        if (n != null)
                            return n;
                    }
                }

                return null;
            }

            @Override
            public Tree visitPackage(PackageTree node, Void p) {
                if (definedBefore(node, cursor)) {
                    return null; // not interested
                }

                return node;
            }

            private boolean within(Tree node, int cursor) {
                var start = sourcePositions.getStartPosition(tree, node);
                if (start > cursor || start == -1) return false;

                var end = sourcePositions.getEndPosition(tree, node);
                if (end == -1) return false;

                var result = cursor >= start && cursor <= end;
                if (result)
                    LOG.fine(node.getKind() + " start: " + start + ", end: " + end + ", cursor: " + cursor);

                return result;
            }

            private boolean definedBefore(Tree node, int cursor) {
                var end = sourcePositions.getEndPosition(tree, node);
                return end < cursor;
            }
        };

        var t0 = System.currentTimeMillis();
        var found = tree.accept(finder, null);
        LOG.fine(() -> "Found " + found + " " + (found != null ? found.getKind() : "")
                     + " in " + (System.currentTimeMillis() - t0) + " ms");
        return found;
    }

    class ResolveElement extends TreeScanner<Element, String> {

        @Override
        public Element visitIdentifier(IdentifierTree node, String p) {
            LOG.fine(() -> "RESOLVE node: " + node + ", node.kind: " + node.getKind());
            var name = node.getName().toString();

            var stream = Stream.<Element>empty();

            var sc = scope;
            var ec = scope.getEnclosingClass();

            do {
                var current = Stream.concat(
                                  StreamSupport.stream(sc.getLocalElements().spliterator(), false),
                                  elementsUtil.getAllMembers(ec).stream()
                              );

                stream = Stream.concat(stream, current);

                sc = sc.getEnclosingScope();
                ec = sc.getEnclosingClass();

            } while (ec != null);

            return stream.filter(el -> el.getSimpleName().contentEquals(name))
                         .findFirst()
                         .orElse(null);
        }

        @Override
        public Element visitMethodInvocation(MethodInvocationTree node, String p) {
            var method = scan(node.getMethodSelect(), p);
            LOG.fine(() -> "RESOLVE node: " + node + ", method: " + method);

            return method;
        }

        @Override
        public Element visitMemberSelect(MemberSelectTree node, String p) {
            LOG.fine(() -> "RESOLVE node: " + node);
            var expression = node.getExpression();
            var elem = scan(expression, p);
            if (elem == null) {
                LOG.fine(() -> "RESOLVE No element found for " + expression);
                return super.visitMemberSelect(node, p);
            }

            var identifier = node.getIdentifier();
            var type = asTypeElement(elem);

            return elementsUtil.getAllMembers(type)
                    .stream()
                    .filter(e -> e.getSimpleName().contentEquals(identifier))
                    .map(e -> {
                        if (e.asType() instanceof NoType) return null;

                        // Handles generic executable elements
                        if (elem.asType() instanceof DeclaredType dt
                                && typesUtil.asMemberOf(dt, e) instanceof ExecutableType exe) {
                            return typesUtil.asElement(exe.getReturnType());
                        }

                        return e;
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Element visitParenthesized(ParenthesizedTree node, String p) {
            LOG.fine(() -> "RESOLVE node: " + node);
            return scan(node.getExpression(), p);
        }

    }

}
