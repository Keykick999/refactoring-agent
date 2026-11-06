package com.aicompany.agentharness.llm;

import com.knuddels.jtokkit.api.Encoding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TiktokenTokenEstimatorTest {

	private static final String ENGLISH = """
			Analyze the class dependencies in this package and report only relationships that are visible in the source.
			Do not invent types that are not declared here.
			""";

	private static final String JAVA_SOURCE = """
			package com.example.graph;
			public final class CodeNodeExtractor {
			    public List<CodeNode> extract(String source) {
			        return List.of(new CodeNode("Example", CodeNodeType.CLASS));
			    }
			}
			""";

	private static final String JSON_SCHEMA = """
			{"type":"object","properties":{"type":{"type":"string","enum":["USE_TOOL","FINISH"]}},"required":["type"]}
			""";

	private final TokenEstimator estimator = TiktokenTokenEstimator.create();
	private final TokenEstimator characters = new ApproximateTokenEstimator();

	@Test
	void countsEnglishBelowTheCharacterEstimate() {
		long tokens = estimator.estimateTokens(ENGLISH);
		long characterEstimate = characters.estimateTokens(ENGLISH);

		assertThat(tokens).isPositive().isLessThan(characterEstimate);
	}

	@Test
	void countsJavaSourceBelowTheCharacterEstimate() {
		long tokens = estimator.estimateTokens(JAVA_SOURCE);
		long characterEstimate = characters.estimateTokens(JAVA_SOURCE);

		assertThat(tokens).isPositive().isLessThan(characterEstimate);
	}

	@Test
	void countsJsonSchemaBelowTheCharacterEstimate() {
		long tokens = estimator.estimateTokens(JSON_SCHEMA);

		assertThat(tokens).isPositive().isLessThan(characters.estimateTokens(JSON_SCHEMA));
	}

	@Test
	void sizesTheWholeRequestFromEveryMessageAndTheSchema() {
		LlmRequest request = new LlmRequest(List.of(
				Message.system("You solve one analysis task."),
				Message.user(JAVA_SOURCE),
				Message.user("Tool result of directory_tree (success):\n" + ENGLISH)),
				JSON_SCHEMA);

		long sized = LlmRequestSizer.estimate(estimator, request);
		long parts = estimator.estimateTokens("SYSTEM")
				+ estimator.estimateTokens("You solve one analysis task.")
				+ estimator.estimateTokens("USER")
				+ estimator.estimateTokens(JAVA_SOURCE)
				+ estimator.estimateTokens("USER")
				+ estimator.estimateTokens("Tool result of directory_tree (success):\n" + ENGLISH)
				+ estimator.estimateTokens(JSON_SCHEMA);

		assertThat(sized).isEqualTo(parts);
	}

	@Test
	void allowsTheGraphPackageUnderTheDefaultBudget() throws IOException {
		String source = graphPackageSource();
		LlmRequest request = new LlmRequest(List.of(
				Message.system(ENGLISH),
				Message.user(source),
				Message.user("Tool result of list_directory (success):\nCodeNode.java\nCodeNodeExtractor.java")),
				JSON_SCHEMA);
		long tokenEstimate = LlmRequestSizer.estimate(estimator, request);
		long characterEstimate = LlmRequestSizer.estimate(characters, request);

		assertThat(characterEstimate).isGreaterThan(6000);
		assertThat(tokenEstimate).isBetween(3000L, 6000L);

		AtomicInteger calls = new AtomicInteger();
		new RequestBudgetLlm(ignored -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		}, estimator, 6000).generate(request);

		assertThat(calls).hasValue(1);
	}

	@Test
	void fallsBackToTheCharacterEstimateWhenTokenizationFails() {
		Encoding encoding = mock(Encoding.class);
		when(encoding.countTokens(anyString())).thenThrow(new IllegalStateException("broken ranks"));
		TokenEstimator estimator = new TiktokenTokenEstimator(encoding, characters);

		assertThat(estimator.estimateTokens("abcd")).isEqualTo(2);
	}

	private static String graphPackageSource() throws IOException {
		Path directory = Path.of("src/main/java/com/aicompany/agentharness/graph");
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(path -> path.toString().endsWith(".java"))
					.sorted()
					.map(TiktokenTokenEstimatorTest::read)
					.reduce("", (left, right) -> left + right);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

}
