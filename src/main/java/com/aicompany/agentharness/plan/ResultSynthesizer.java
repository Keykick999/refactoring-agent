package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;

import java.util.List;

public final class ResultSynthesizer {

	private static final String SYSTEM_PROMPT = """
			You write the final analysis report from stored task results only.
			Do not ask for source files again. Summarize findings, related files, recommendations, and coverage.
			""";

	private final Llm llm;
	private final SynthesisParser parser = new SynthesisParser();

	public ResultSynthesizer(Llm llm) {
		this.llm = llm;
	}

	public FinalAnalysisResult synthesize(AnalysisPlan plan) {
		String response = llm.generate(new LlmRequest(List.of(Message.system(SYSTEM_PROMPT), Message.user(userMessage(plan))),
				SynthesisParser.RESPONSE_JSON_SCHEMA)).content();
		return parser.parse(plan.request().objective(), response);
	}

	private static String userMessage(AnalysisPlan plan) {
		StringBuilder text = new StringBuilder();
		text.append("## Objective\n").append(plan.request().objective()).append('\n');
		text.append("## Task results\n");
		for (PlannedTask task : plan.completedOutcomes()) {
			text.append("- ").append(task.id()).append(": ").append(task.outcome().conclusion()).append('\n');
			for (String fact : task.outcome().facts()) {
				text.append("  fact: ").append(fact).append('\n');
			}
		}
		return text.toString();
	}

}
