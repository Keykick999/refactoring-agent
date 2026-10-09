package com.aicompany.agentharness.context;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkUnitPlannerTest {

	private static final String PACKAGE_A = Path.of("com/example/a").toString();
	private static final String PACKAGE_B = Path.of("com/example/b").toString();

	private final SourceFile aEntity = file("com/example/a/A.java", 30);
	private final SourceFile aRepository = file("com/example/a/ARepository.java", 40);
	private final SourceFile aService = file("com/example/a/AService.java", 50);
	private final SourceFile bRepository = file("com/example/b/BRepository.java", 20);
	private final SourceFile bService = file("com/example/b/BService.java", 25);

	@Test
	void groupsFilesInSamePackageIntoOneWorkUnit() {
		List<WorkUnit> workUnits = planner(1_000).plan(List.of(aService, aRepository, aEntity));

		assertThat(workUnits).containsExactly(new WorkUnit(PACKAGE_A, List.of(aEntity, aRepository, aService), 120, false));
	}

	@Test
	void separatesFilesInDifferentPackages() {
		List<WorkUnit> workUnits = planner(1_000).plan(List.of(bService, aService, bRepository, aEntity));

		assertThat(workUnits).containsExactly(
				new WorkUnit(PACKAGE_A, List.of(aEntity, aService), 80, false),
				new WorkUnit(PACKAGE_B, List.of(bRepository, bService), 45, false));
	}

	@Test
	void splitsPackageIntoMultipleWorkUnitsWhenItExceedsMaxContextSize() {
		List<WorkUnit> workUnits = planner(100).plan(List.of(aEntity, aRepository, aService));

		assertThat(workUnits).containsExactly(
				new WorkUnit(PACKAGE_A, List.of(aEntity, aRepository), 70, false),
				new WorkUnit(PACKAGE_A, List.of(aService), 50, false));
		assertThat(workUnits).allSatisfy(workUnit -> assertThat(workUnit.contextSize()).isLessThanOrEqualTo(100));
	}

	@Test
	void keepsFileLargerThanMaxContextSizeWholeInItsOwnFlaggedWorkUnit() {
		SourceFile hugeService = file("com/example/a/HugeService.java", 500);

		List<WorkUnit> workUnits = planner(100).plan(List.of(aEntity, hugeService, aRepository, aService));

		assertThat(workUnits).containsExactly(
				new WorkUnit(PACKAGE_A, List.of(aEntity, aRepository), 70, false),
				new WorkUnit(PACKAGE_A, List.of(aService), 50, false),
				new WorkUnit(PACKAGE_A, List.of(hugeService), 500, true));
	}

	@Test
	void keepsFileContentsUnchangedAndIncludesEveryFileExactlyOnce() {
		SourceFile hugeService = file("com/example/a/HugeService.java", 500);
		List<SourceFile> sourceFiles = List.of(aEntity, aRepository, aService, hugeService, bRepository, bService);

		List<WorkUnit> workUnits = planner(60).plan(sourceFiles);

		List<SourceFile> plannedFiles = workUnits.stream().flatMap(workUnit -> workUnit.files().stream()).toList();
		assertThat(plannedFiles).containsExactlyInAnyOrderElementsOf(sourceFiles);
		assertThat(plannedFiles).filteredOn(file -> file.path().equals(hugeService.path()))
				.singleElement()
				.extracting(SourceFile::content)
				.isEqualTo(hugeService.content());
	}

	@Test
	void producesSameWorkUnitsRegardlessOfInputOrder() {
		List<SourceFile> sourceFiles = new ArrayList<>(List.of(aEntity, aRepository, aService, bRepository, bService));
		List<WorkUnit> expected = planner(80).plan(sourceFiles);

		Random random = new Random(42);
		for (int i = 0; i < 20; i++) {
			Collections.shuffle(sourceFiles, random);
			assertThat(planner(80).plan(sourceFiles)).isEqualTo(expected);
		}
	}

	@Test
	void usesReplaceableGroupingStrategy() {
		GroupingStrategy singleGroup = sourceFiles -> Map.of("all", sourceFiles);

		List<WorkUnit> workUnits = new WorkUnitPlanner(singleGroup, ContextSizeEstimator.CHARACTERS, 1_000)
				.plan(List.of(bService, aEntity));

		assertThat(workUnits).containsExactly(new WorkUnit("all", List.of(aEntity, bService), 55, false));
	}

	@Test
	void rejectsGroupingStrategyThatDropsFiles() {
		GroupingStrategy droppingStrategy = sourceFiles -> Map.of("first", List.of(sourceFiles.get(0)));

		assertThatThrownBy(() -> new WorkUnitPlanner(droppingStrategy, ContextSizeEstimator.CHARACTERS, 1_000)
				.plan(List.of(aEntity, aService)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void returnsNoWorkUnitsForNoFiles() {
		assertThat(planner(100).plan(List.of())).isEmpty();
	}

	private static WorkUnitPlanner planner(int maxContextSize) {
		return new WorkUnitPlanner(new PackageGroupingStrategy(), ContextSizeEstimator.CHARACTERS, maxContextSize);
	}

	/**
	 * 지정한 길이의 내용을 가진 파일. 경로마다 내용이 달라, 내용이 다른 파일과 섞이거나 잘리면 비교에서 드러난다.
	 */
	private static SourceFile file(String path, int length) {
		String seed = "// " + path + "\n";
		return new SourceFile(Path.of(path), seed.repeat(length / seed.length() + 1).substring(0, length));
	}

}
