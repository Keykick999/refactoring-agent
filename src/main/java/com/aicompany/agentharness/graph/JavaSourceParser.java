package com.aicompany.agentharness.graph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * target 아래 Java 소스만 AST로 읽는다. 타입 이름은 package 선언으로 계산한 source root와 JDK로 resolve한다.
 */
final class JavaSourceParser {

	List<ParsedJavaSource> parse(Path target) {
		if (target == null) {
			throw new IllegalArgumentException("target must not be null");
		}
		if (!Files.isDirectory(target)) {
			throw new IllegalArgumentException("target must be a directory: " + target);
		}
		Path normalizedTarget = target.toAbsolutePath().normalize();
		JavaParser parser = new JavaParser(new ParserConfiguration()
				.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
				.setCharacterEncoding(StandardCharsets.UTF_8));

		List<ParsedJavaSource> sources = new ArrayList<>();
		for (Path file : javaFiles(normalizedTarget)) {
			sources.add(new ParsedJavaSource(file.toAbsolutePath().normalize(), parseFile(parser, file)));
		}
		JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver(JavaSourceRoots.resolve(normalizedTarget, sources)));
		sources.forEach(source -> symbolSolver.inject(source.compilationUnit()));
		return List.copyOf(sources);
	}

	private static CombinedTypeSolver typeSolver(List<Path> sourceRoots) {
		CombinedTypeSolver solver = new CombinedTypeSolver();
		sourceRoots.forEach(sourceRoot -> solver.add(new JavaParserTypeSolver(sourceRoot)));
		solver.add(new ReflectionTypeSolver(true));
		return solver;
	}

	private static List<Path> javaFiles(Path target) {
		try (Stream<Path> paths = Files.walk(target)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()))
					.toList();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not list Java sources in " + target, exception);
		}
	}

	private static CompilationUnit parseFile(JavaParser parser, Path file) {
		try {
			ParseResult<CompilationUnit> parsed = parser.parse(file);
			return parsed.getResult().orElseThrow(() -> new IllegalStateException(
					"Could not parse " + file + ": " + parsed.getProblems()));
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not read " + file, exception);
		}
	}

}
