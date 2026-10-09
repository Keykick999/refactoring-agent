package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.PendingClass;

import java.util.List;

public record PreparedAnalysis(long jobId, List<PendingClass> pending) {

	public PreparedAnalysis {
		pending = List.copyOf(pending);
	}

}
