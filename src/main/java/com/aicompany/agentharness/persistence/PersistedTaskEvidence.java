package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.TaskEvidence;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class PersistedTaskEvidence {

	@Column(name = "file_path", length = 2000)
	private String filePath;

	@Column(name = "class_name", length = 500)
	private String className;

	@Column(name = "method_name", length = 500)
	private String methodName;

	@Column(name = "detail_text", length = 2000)
	private String detail;

	protected PersistedTaskEvidence() {
	}

	public PersistedTaskEvidence(TaskEvidence evidence) {
		this.filePath = evidence.filePath();
		this.className = evidence.className();
		this.methodName = evidence.methodName();
		this.detail = evidence.detail();
	}

	public TaskEvidence toEvidence() {
		return new TaskEvidence(filePath, className, methodName, detail);
	}

}
