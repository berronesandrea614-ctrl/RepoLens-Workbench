package com.repolens.service.impl.support;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

public final class SpringApiAnnotationResolver {

    private SpringApiAnnotationResolver() {
    }

    public static boolean isController(TypeDeclaration<?> typeDeclaration) {
        return typeDeclaration.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name -> "RestController".equals(name) || "Controller".equals(name));
    }

    public static String resolveClassLevelPath(TypeDeclaration<?> typeDeclaration) {
        for (AnnotationExpr annotation : typeDeclaration.getAnnotations()) {
            String simpleName = simpleName(annotation.getNameAsString());
            if ("RequestMapping".equals(simpleName)) {
                return normalizePath(extractPath(annotation).orElse(null));
            }
        }
        return null;
    }

    public static ApiMapping resolveApiMapping(MethodDeclaration methodDeclaration, String classLevelPath) {
        for (AnnotationExpr annotation : methodDeclaration.getAnnotations()) {
            String annotationName = simpleName(annotation.getNameAsString());
            if ("GetMapping".equals(annotationName)) {
                return new ApiMapping(joinPath(classLevelPath, normalizePath(extractPath(annotation).orElse(null))), "GET");
            }
            if ("PostMapping".equals(annotationName)) {
                return new ApiMapping(joinPath(classLevelPath, normalizePath(extractPath(annotation).orElse(null))), "POST");
            }
            if ("PutMapping".equals(annotationName)) {
                return new ApiMapping(joinPath(classLevelPath, normalizePath(extractPath(annotation).orElse(null))), "PUT");
            }
            if ("DeleteMapping".equals(annotationName)) {
                return new ApiMapping(joinPath(classLevelPath, normalizePath(extractPath(annotation).orElse(null))), "DELETE");
            }
            if ("PatchMapping".equals(annotationName)) {
                return new ApiMapping(joinPath(classLevelPath, normalizePath(extractPath(annotation).orElse(null))), "PATCH");
            }
            if ("RequestMapping".equals(annotationName)) {
                String methodLevelPath = normalizePath(extractPath(annotation).orElse(null));
                String apiPath = joinPath(classLevelPath, methodLevelPath);
                String httpMethod = resolveRequestMappingHttpMethod(annotation);
                return new ApiMapping(apiPath, httpMethod);
            }
        }
        return null;
    }

    private static String resolveRequestMappingHttpMethod(AnnotationExpr annotationExpr) {
        if (!(annotationExpr instanceof NormalAnnotationExpr normalAnnotationExpr)) {
            return null;
        }
        for (var pair : normalAnnotationExpr.getPairs()) {
            if (!"method".equals(pair.getNameAsString())) {
                continue;
            }
            return parseRequestMethodExpression(pair.getValue());
        }
        return null;
    }

    private static String parseRequestMethodExpression(Expression expression) {
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccessExpr.getNameAsString().toUpperCase(Locale.ROOT);
        }
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString().toUpperCase(Locale.ROOT);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr
                && !arrayInitializerExpr.getValues().isEmpty()) {
            return parseRequestMethodExpression(arrayInitializerExpr.getValues().get(0));
        }
        return null;
    }

    private static Optional<String> extractPath(AnnotationExpr annotationExpr) {
        if (annotationExpr instanceof SingleMemberAnnotationExpr singleMemberAnnotationExpr) {
            return extractStringValue(singleMemberAnnotationExpr.getMemberValue());
        }
        if (annotationExpr instanceof NormalAnnotationExpr normalAnnotationExpr) {
            Optional<String> valuePath = normalAnnotationExpr.getPairs().stream()
                    .filter(pair -> "value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString()))
                    .findFirst()
                    .flatMap(pair -> extractStringValue(pair.getValue()));
            if (valuePath.isPresent()) {
                return valuePath;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractStringValue(Expression expression) {
        if (expression instanceof StringLiteralExpr stringLiteralExpr) {
            return Optional.ofNullable(stringLiteralExpr.asString());
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            for (Expression value : arrayInitializerExpr.getValues()) {
                Optional<String> parsed = extractStringValue(value);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private static String simpleName(String annotationName) {
        int index = annotationName.lastIndexOf('.');
        return index < 0 ? annotationName : annotationName.substring(index + 1);
    }

    private static String joinPath(String classLevelPath, String methodLevelPath) {
        String left = normalizePath(classLevelPath);
        String right = normalizePath(methodLevelPath);

        if (!StringUtils.hasText(left) && !StringUtils.hasText(right)) {
            return null;
        }
        if (!StringUtils.hasText(left)) {
            return right;
        }
        if (!StringUtils.hasText(right)) {
            return left;
        }

        if (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        if (!right.startsWith("/")) {
            right = "/" + right;
        }
        return (left + right).replaceAll("/{2,}", "/");
    }

    private static String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return trimmed.replaceAll("/{2,}", "/");
    }

    public record ApiMapping(String apiPath, String httpMethod) {
    }
}
