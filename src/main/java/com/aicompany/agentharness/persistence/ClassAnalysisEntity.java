package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.ClassProgress;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "class_analysis", uniqueConstraints = @UniqueConstraint(name = "uk_class_analysis_path",
		columnNames = {"job_id", "file_path"}))
public class ClassAnalysisEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "job_id", nullable = false)
	private AnalysisJobEntity job;

	@Column(name = "class_name", nullable = false, length = 500)
	private String className;

	@Column(name = "file_path", nullable = false, length = 2000)
	private String filePath;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ClassProgress status;

	@Enumerated(EnumType.STRING)
	@Column(name = "error_category", length = 32)
	private LlmErrorCategory errorCategory;

	@Column(name = "error_message", length = 4000)
	private String errorMessage;

	@Column(name = "user_action", length = 1000)
	private String userAction;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@OneToMany(mappedBy = "classAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<PersistedCodeIssue> issues = new ArrayList<>();

	protected ClassAnalysisEntity() {
	}

	public ClassAnalysisEntity(String className, String filePath) {
		this.className = className;
		this.filePath = filePath;
		this.status = ClassProgress.PENDING;
	}

	public String getClassName() {
		return className;
	}

	public String getFilePath() {
		return filePath;
	}

	public ClassProgress getStatus() {
		return status;
	}

	public LlmErrorCategory getErrorCategory() {
		return errorCategory;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getUserAction() {
		return userAction;
	}

	public List<PersistedCodeIssue> getIssues() {
		return issues;
	}

	public void setJob(AnalysisJobEntity job) {
		this.job = job;
	}

	public void markPending() {
		this.status = ClassProgress.PENDING;
	}

	public void markRunning() {
		this.status = ClassProgress.RUNNING;
		this.attemptCount++;
	}

	public void replace(ClassProgress status, LlmErrorCategory category, String errorMessage, String userAction,
			List<PersistedCodeIssue> replacement) {
		this.status = status;
		this.errorCategory = category;
		this.errorMessage = errorMessage;
		this.userAction = userAction;
		this.issues.clear();
		for (PersistedCodeIssue issue : replacement) {
			issue.setClassAnalysis(this);
			this.issues.add(issue);
		}
	}

}
