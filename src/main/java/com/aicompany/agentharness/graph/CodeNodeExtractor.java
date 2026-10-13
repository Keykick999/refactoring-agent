package com.aicompany.agentharness.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 컴파일 단위에서 클래스와 인터페이스 노드를 뽑는다. enum과 annotation은 노드로 만들지 않는다.
 */
final class CodeNodeExtractor {

	List<CodeNode> extract(ParsedJavaSource source) {
		CompilationUnit unit = source.compilationUnit();
		List<CodeNode> nodes = new ArrayList<>();
		for (ClassOrInterfaceDeclaration declaration : unit.findAll(ClassOrInterfaceDeclaration.class)) {
			toNode(source.filePath(), declaration, declaration.isInterface() ? CodeNodeType.INTERFACE : CodeNodeType.CLASS)
					.ifPresent(nodes::add);
		}
		for (RecordDeclaration declaration : unit.findAll(RecordDeclaration.class)) {
			toNode(source.filePath(), declaration, CodeNodeType.CLASS).ifPresent(nodes::add);
		}
		return List.copyOf(nodes);
	}

	private static Optional<CodeNode> toNode(Path filePath, TypeDeclaration<?> declaration, CodeNodeType type) {
		return declaration.getFullyQualifiedName()
				.map(qualifiedName -> new CodeNode(type, declaration.getNameAsString(), qualifiedName, filePath));
	}

}
