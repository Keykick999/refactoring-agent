package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.harness.Agent;
import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Java 파일 하나의 원문만 LLM에 보내 코드 품질을 분석한다.
 *
 * <p>코드는 이미 읽어 둔 상태이므로 Tool을 고르지 않고, 한 번의 LLM 호출로 FINISH한다.
 * 재시도는 주입된 Llm(RetryingLlm)이 담당한다.
 */
public class ClassCodeQualityAgent implements Agent {

	private static final Logger log = LoggerFactory.getLogger(ClassCodeQualityAgent.class);

	private static final String SYSTEM_PROMPT = """
			You are a Code Quality Agent.
			Analyze the single Java class in the user message. Do not analyze any other class.
			Look only at what is visible inside this class: readability, maintainability, duplication,
			complexity, responsibility, naming, coupling, cohesion, and design.
			Do not infer relationships with classes that are not in the source.
			Do not modify files. Recommendations are text only.
			If the class has no quality issues, return an empty issues array.
			Respond with a single JSON object: {"issues": [ ... ]}.
			""";

	private final Llm llm;
	private final DiscoveredJavaSource source;
	private final String objective;
	private final ClassAnalysisCollector collector;
	private final ClassAnalysisParser parser = new ClassAnalysisParser();

	public ClassCodeQualityAgent(Llm llm, DiscoveredJavaSource source, String objective, ClassAnalysisCollector collector) {
		this.llm = llm;
		this.source = source;
		this.objective = objective;
		this.collector = collector;
	}

	@Override
	public AgentDecision decide(AgentState agentState) {
		String className = classNameOf(source.path());
		String response;
		try {
			response = llm.generate(new LlmRequest(createMessages(), ClassAnalysisParser.RESPONSE_JSON_SCHEMA)).content();
		}
		catch (LlmException exception) {
			log.warn("Class analysis failed for {}", source.path(), exception);
			record(ClassAnalysisResult.failed(className, source.path(), "LLM call failed: " + exception.getMessage(), exception.category()));
			return AgentDecision.finish(AgentResult.failure("LLM call failed: " + exception.getMessage()));
		}

		ClassAnalysisResult result = parser.parse(className, source.path(), response);
		record(result);
		if (result.status() != AnalysisStatus.SUCCEEDED) {
			return AgentDecision.finish(AgentResult.failure(result.error()));
		}
		return AgentDecision.finish(AgentResult.success(className + ": " + result.issues().size() + " issues"));
	}

	private void record(ClassAnalysisResult result) {
		if (!collector.add(result)) {
			log.warn("Ignoring duplicate analysis result for {}", result.path());
		}
	}

	private List<Message> createMessages() {
		return java.util.List.of(Message.system(SYSTEM_PROMPT), Message.user("""
				## Task
				Objective: %s
				Class: %s
				File: %s

				## Source
				%s
				""".formatted(objective, classNameOf(source.path()), source.path(), source.content())));
	}

	static String classNameOf(Path path) {
		String fileName = path.getFileName().toString();
		return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
	}

}
