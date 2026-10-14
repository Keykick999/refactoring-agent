package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.PlanTaskStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "planned_task", uniqueConstraints = @UniqueConstraint(name = "uk_planned_task_key",
		columnNames = {"plan_id", "task_key"}))
public class PlannedTaskEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "plan_id", nullable = false)
	private AnalysisPlanEntity plan;

	@Column(name = "task_key", nullable = false, length = 200)
	private String taskKey;

	@Column(nullable = false, length = 2000)
	private String objective;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PlanTaskStatus status;

	@ElementCollection
	@CollectionTable(name = "planned_task_dependency", joinColumns = @JoinColumn(name = "task_id"))
	@OrderColumn(name = "dependency_order")
	@Column(name = "depends_on", length = 200)
	private List<String> dependsOn = new ArrayList<>();

	@Column(length = 4000)
	private String conclusion;

	@ElementCollection
	@CollectionTable(name = "planned_task_fact", joinColumns = @JoinColumn(name = "task_id"))
	@OrderColumn(name = "fact_order")
	@Column(name = "fact_text", length = 2000)
	private List<String> facts = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "planned_task_question", joinColumns = @JoinColumn(name = "task_id"))
	@OrderColumn(name = "question_order")
	@Column(name = "question_text", length = 2000)
	private List<String> unresolvedQuestions = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "planned_task_evidence", joinColumns = @JoinColumn(name = "task_id"))
	@OrderColumn(name = "evidence_order")
	private List<PersistedTaskEvidence> evidence = new ArrayList<>();

	protected PlannedTaskEntity() {
	}

	public PlannedTaskEntity(String taskKey, String objective, List<String> dependsOn) {
		this.taskKey = taskKey;
		this.objective = objective;
		this.status = PlanTaskStatus.PENDING;
		this.dependsOn = new ArrayList<>(dependsOn);
	}

	public String getTaskKey() {
		return taskKey;
	}

	public String getObjective() {
		return objective;
	}

	public PlanTaskStatus getStatus() {
		return status;
	}

	public List<String> getDependsOn() {
		return dependsOn;
	}

	public String getConclusion() {
		return conclusion;
	}

	public List<String> getFacts() {
		return facts;
	}

	public List<String> getUnresolvedQuestions() {
		return unresolvedQuestions;
	}

	public List<PersistedTaskEvidence> getEvidence() {
		return evidence;
	}

	public void setPlan(AnalysisPlanEntity plan) {
		this.plan = plan;
	}

	public void setStatus(PlanTaskStatus status) {
		this.status = status;
	}

	public void setDependsOn(List<String> dependsOn) {
		this.dependsOn = new ArrayList<>(dependsOn);
	}

	public void replaceOutcome(PlanTaskStatus status, String conclusion, List<String> facts, List<String> unresolvedQuestions,
			List<PersistedTaskEvidence> evidence) {
		this.status = status;
		this.conclusion = conclusion;
		this.facts.clear();
		this.facts.addAll(facts);
		this.unresolvedQuestions.clear();
		this.unresolvedQuestions.addAll(unresolvedQuestions);
		this.evidence.clear();
		this.evidence.addAll(evidence);
	}

}
