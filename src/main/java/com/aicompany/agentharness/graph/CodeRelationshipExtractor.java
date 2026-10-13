package com.aicompany.agentharness.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 필드, 생성자/메서드 시그니처, extends, implements에서 프로젝트 내부 타입으로의 관계만 뽑는다.
 * resolve되지 않은 이름은 단순 이름만으로 연결하지 않는다.
 */
final class CodeRelationshipExtractor {

	List<CodeRelationshipDraft> extract(ParsedJavaSource source, Map<String, CodeNode> nodes) {
		CompilationUnit unit = source.compilationUnit();
		List<CodeRelationshipDraft> drafts = new ArrayList<>();
		for (ClassOrInterfaceDeclaration declaration : unit.findAll(ClassOrInterfaceDeclaration.class)) {
			extractType(declaration, declaration.getExtendedTypes(), declaration.getImplementedTypes(), nodes, drafts);
		}
		for (RecordDeclaration declaration : unit.findAll(RecordDeclaration.class)) {
			extractType(declaration, List.of(), declaration.getImplementedTypes(), nodes, drafts);
		}
		return List.copyOf(drafts);
	}

	private void extractType(TypeDeclaration<?> declaration, List<ClassOrInterfaceType> extendedTypes,
			List<ClassOrInterfaceType> implementedTypes, Map<String, CodeNode> nodes, List<CodeRelationshipDraft> drafts) {
		String sourceName = declaration.getFullyQualifiedName().orElse(null);
		if (sourceName == null || !nodes.containsKey(sourceName)) {
			return;
		}
		for (ClassOrInterfaceType extended : extendedTypes) {
			addPrimary(sourceName, extended, RelationshipType.EXTENDS, RelationshipSource.EXTENDS, nodes, drafts);
		}
		for (ClassOrInterfaceType implemented : implementedTypes) {
			addPrimary(sourceName, implemented, RelationshipType.IMPLEMENTS, RelationshipSource.IMPLEMENTS, nodes, drafts);
		}
		for (BodyDeclaration<?> member : declaration.getMembers()) {
			if (member instanceof FieldDeclaration field) {
				addDependency(sourceName, field.getElementType(), RelationshipSource.FIELD, nodes, drafts);
			}
			else if (member instanceof ConstructorDeclaration constructor) {
				constructor.getParameters().forEach(parameter -> addDependency(sourceName, parameter.getType(),
						RelationshipSource.CONSTRUCTOR_PARAMETER, nodes, drafts));
			}
			else if (member instanceof MethodDeclaration method) {
				method.getParameters().forEach(parameter -> addDependency(sourceName, parameter.getType(),
						RelationshipSource.METHOD_PARAMETER, nodes, drafts));
				addDependency(sourceName, method.getType(), RelationshipSource.RETURN_TYPE, nodes, drafts);
			}
		}
		if (declaration instanceof RecordDeclaration record) {
			record.getParameters().forEach(parameter -> addDependency(sourceName, parameter.getType(),
					RelationshipSource.CONSTRUCTOR_PARAMETER, nodes, drafts));
		}
	}

	private void addPrimary(String sourceName, Type targetType, RelationshipType relationshipType, RelationshipSource sourceKind,
			Map<String, CodeNode> nodes, List<CodeRelationshipDraft> drafts) {
		for (String targetName : primaryQualifiedName(targetType)) {
			addIfProjectType(sourceName, targetName, relationshipType, sourceKind, nodes, drafts);
		}
	}

	private void addDependency(String sourceName, Type targetType, RelationshipSource sourceKind, Map<String, CodeNode> nodes,
			List<CodeRelationshipDraft> drafts) {
		for (String targetName : dependencyQualifiedNames(targetType)) {
			addIfProjectType(sourceName, targetName, RelationshipType.DEPENDS_ON, sourceKind, nodes, drafts);
		}
	}

	private void addIfProjectType(String sourceName, String targetName, RelationshipType relationshipType,
			RelationshipSource sourceKind, Map<String, CodeNode> nodes, List<CodeRelationshipDraft> drafts) {
		if (targetName == null || !nodes.containsKey(targetName)) {
			return;
		}
		drafts.add(new CodeRelationshipDraft(sourceName, targetName, relationshipType, sourceKind));
	}

	private List<String> primaryQualifiedName(Type type) {
		try {
			ResolvedType resolved = type.resolve();
			if (!resolved.isReferenceType()) {
				return List.of();
			}
			return List.of(resolved.asReferenceType().getQualifiedName());
		}
		catch (UnsolvedSymbolException | UnsupportedOperationException exception) {
			return List.of();
		}
	}

	private List<String> dependencyQualifiedNames(Type type) {
		try {
			List<String> names = new ArrayList<>();
			collectDependencyNames(type.resolve(), names);
			return List.copyOf(names);
		}
		catch (UnsolvedSymbolException | UnsupportedOperationException exception) {
			return List.of();
		}
	}

	private void collectDependencyNames(ResolvedType resolved, List<String> names) {
		if (resolved.isPrimitive() || resolved.isVoid() || resolved.isTypeVariable()) {
			return;
		}
		if (resolved.isArray()) {
			collectDependencyNames(resolved.asArrayType().getComponentType(), names);
			return;
		}
		if (resolved.isWildcard()) {
			if (resolved.asWildcard().isBounded()) {
				collectDependencyNames(resolved.asWildcard().getBoundedType(), names);
			}
			return;
		}
		if (!resolved.isReferenceType()) {
			return;
		}
		ResolvedReferenceType reference = resolved.asReferenceType();
		names.add(reference.getQualifiedName());
		try {
			for (ResolvedType argument : reference.typeParametersValues()) {
				collectDependencyNames(argument, names);
			}
		}
		catch (UnsolvedSymbolException | UnsupportedOperationException exception) {
			// 타입 인자를 풀 수 없으면 그 인자는 관계로 만들지 않는다.
		}
	}

}
