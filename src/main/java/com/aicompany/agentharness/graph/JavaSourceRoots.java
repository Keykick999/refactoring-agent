package com.aicompany.agentharness.graph;

import com.github.javaparser.ast.PackageDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 타입 resolve에 쓸 Java source root를 package 선언으로 계산한다.
 * target은 분석할 파일의 범위이고, source root는 package 이름을 파일 경로로 바꾸는 기준이다.
 * source root가 target보다 위에 있어도 분석 대상 파일은 늘어나지 않는다.
 */
final class JavaSourceRoots {

	private static final Logger log = LoggerFactory.getLogger(JavaSourceRoots.class);

	private JavaSourceRoots() {
	}

	static List<Path> resolve(Path target, List<ParsedJavaSource> sources) {
		Set<Path> roots = new LinkedHashSet<>();
		for (ParsedJavaSource source : sources) {
			Optional<Path> root = sourceRoot(source);
			if (root.isPresent()) {
				roots.add(root.get());
			}
			else {
				log.warn("Package declaration of {} does not match its directory; resolving its types from {} instead",
						source.filePath(), target);
				roots.add(target);
			}
		}
		if (roots.isEmpty()) {
			roots.add(target);
		}
		return roots.stream().sorted().toList();
	}

	static Optional<Path> sourceRoot(ParsedJavaSource source) {
		Path directory = source.filePath().getParent();
		if (directory == null) {
			return Optional.empty();
		}
		String packageName = source.compilationUnit().getPackageDeclaration()
				.map(PackageDeclaration::getNameAsString)
				.orElse("");
		if (packageName.isEmpty()) {
			return Optional.of(directory);
		}
		String[] segments = packageName.split("\\.");
		Path packagePath = directory.getFileSystem().getPath(segments[0], Arrays.copyOfRange(segments, 1, segments.length));
		if (!directory.endsWith(packagePath)) {
			return Optional.empty();
		}
		Path root = directory;
		for (int index = 0; index < packagePath.getNameCount() && root != null; index++) {
			root = root.getParent();
		}
		return Optional.ofNullable(root);
	}

}
