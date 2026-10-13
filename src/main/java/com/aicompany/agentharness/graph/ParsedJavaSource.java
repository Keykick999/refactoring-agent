package com.aicompany.agentharness.graph;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;

record ParsedJavaSource(Path filePath, CompilationUnit compilationUnit) {
}
