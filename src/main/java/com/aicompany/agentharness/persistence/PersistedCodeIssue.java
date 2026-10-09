package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.CodeIssue;
import com.aicompany.agentharness.analysis.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "code_issue")
public class PersistedCodeIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "class_analysis_id", nullable = false)
	private ClassAnalysisEntity classAnalysis;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(nullable = false, length = 4000)
	private String description;

	@Column(nullable = false, length = 10000)
	private String evidence;

	@Column(nullable = false, length = 4000)
	private String impact;

	@Column(nullable = false, length = 4000)
	private String recommendation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Severity severity;

	protected PersistedCodeIssue() {
	}

	public PersistedCodeIssue(CodeIssue issue) {
		this.title = issue.title();
		this.description = issue.description();
		this.evidence = issue.evidence();
		this.impact = issue.impact();
		this.recommendation = issue.recommendation();
		this.severity = issue.severity();
	}

	public void setClassAnalysis(ClassAnalysisEntity classAnalysis) {
		this.classAnalysis = classAnalysis;
	}

	public CodeIssue toIssue() {
		return new CodeIssue(title, description, evidence, impact, recommendation, severity);
	}

}
