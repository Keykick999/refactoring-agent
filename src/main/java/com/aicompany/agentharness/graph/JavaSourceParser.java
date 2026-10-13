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
 * Java 소스를 AST로 읽는다. 파서는 JavaParser를 쓰고, 타입 이름은 소스 루트와 JDK만으로 resolve한다.
 */
final class JavaSourceParser {

	List<ParsedJavaSource> parse(Path sourceRoot) {
		if (sourceRoot == null) {
			throw new IllegalArgumentException("sourceRoot must not be null");
		}
		if (!Files.isDirectory(sourceRoot)) {
			throw new IllegalArgumentException("sourceRoot must be a directory: " + sourceRoot);
		}
		ParserConfiguration configuration = new ParserConfiguration()
				.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
				.setCharacterEncoding(StandardCharsets.UTF_8)
				.setSymbolResolver(new JavaSymbolSolver(typeSolver(sourceRoot)));
		JavaParser parser = new JavaParser(configuration);

		List<ParsedJavaSource> sources = new ArrayList<>();
		for (Path file : javaFiles(sourceRoot)) {
			sources.add(new ParsedJavaSource(file.toAbsolutePath().normalize(), parseFile(parser, file)));
		}
		return List.copyOf(sources);
	}

	private static CombinedTypeSolver typeSolver(Path sourceRoot) {
		CombinedTypeSolver solver = new CombinedTypeSolver();
		solver.add(new JavaParserTypeSolver(sourceRoot));
		solver.add(new ReflectionTypeSolver(true));
		return solver;
	}

	private static List<Path> javaFiles(Path sourceRoot) {
		try (Stream<Path> paths = Files.walk(sourceRoot)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()))
					.toList();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not list Java sources in " + sourceRoot, exception);
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
