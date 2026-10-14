package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;

import java.util.List;

public final class FinalChecker {

	private static final String SYSTEM_PROMPT = """
			You review whether the overall analysis objective is solved with the current task results.
			If an important gap remains, set needsMoreWork=true and propose new tasks.
			If the remaining pending tasks cannot run because they are blocked, you may still propose unblocking tasks.
			Do not reload the whole source. Use stored facts, conclusions, and unresolved questions.
			""";

	private final Llm llm;
	private final FinalCheckParser parser = new FinalCheckParser();

	public FinalChecker(Llm llm) {
		this.llm = llm;
	}

	public FinalCheckDecision check(AnalysisPlan plan) {
		String response = llm.generate(new LlmRequest(List.of(Message.system(SYSTEM_PROMPT), Message.user(userMessage(plan))),
				FinalCheckParser.RESPONSE_JSON_SCHEMA)).content();
		return parser.parse(response);
	}

	private static String userMessage(AnalysisPlan plan) {
		StringBuilder text = new StringBuilder();
		text.append("## Objective\n").append(plan.request().objective()).append('\n');
		text.append("## Style\n").append(plan.request().style()).append('\n');
		text.append("## ").append(plan.progress().render()).append('\n');
		text.append("## Tasks\n");
		for (PlannedTask task : plan.tasks()) {
			text.append("- ").append(task.id()).append(" [").append(task.status()).append("] ").append(task.objective()).append('\n');
			if (task.outcome() != null) {
				text.append("  conclusion: ").append(task.outcome().conclusion()).append('\n');
				for (String question : task.outcome().unresolvedQuestions()) {
					text.append("  unresolved: ").append(question).append('\n');
				}
			}
		}
		return text.toString();
	}

}
