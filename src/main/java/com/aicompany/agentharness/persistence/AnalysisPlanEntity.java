package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.plan.AnalysisStyle;
import com.aicompany.agentharness.plan.EnforcementMode;
import com.aicompany.agentharness.plan.PlanStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "analysis_plan", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_plan_identity",
		columnNames = {"target_path", "objective", "settings_fingerprint"}))
public class AnalysisPlanEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "target_path", nullable = false, length = 2000)
	private String targetPath;

	@Column(nullable = false, length = 2000)
	private String objective;

	@Column(name = "settings_fingerprint", nullable = false, length = 500)
	private String settingsFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private AnalysisStyle style;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private EnforcementMode enforcementMode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PlanStatus status;

	@Column(length = 4000)
	private String statusReason;

	@Column(length = 16000)
	private String finalResultJson;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("taskKey")
	private List<PlannedTaskEntity> tasks = new ArrayList<>();

	protected AnalysisPlanEntity() {
	}

	public AnalysisPlanEntity(String targetPath, String objective, String settingsFingerprint, AnalysisStyle style,
			EnforcementMode enforcementMode, Instant createdAt) {
		this.targetPath = targetPath;
		this.objective = objective;
		this.settingsFingerprint = settingsFingerprint;
		this.style = style;
		this.enforcementMode = enforcementMode;
		this.status = PlanStatus.RUNNING;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getTargetPath() {
		return targetPath;
	}

	public String getObjective() {
		return objective;
	}

	public AnalysisStyle getStyle() {
		return style;
	}

	public EnforcementMode getEnforcementMode() {
		return enforcementMode;
	}

	public PlanStatus getStatus() {
		return status;
	}

	public String getFinalResultJson() {
		return finalResultJson;
	}

	public List<PlannedTaskEntity> getTasks() {
		return tasks;
	}

	public void setStatus(PlanStatus status, String reason) {
		this.status = status;
		this.statusReason = reason;
		this.updatedAt = Instant.now();
	}

	public void setFinalResultJson(String finalResultJson) {
		this.finalResultJson = finalResultJson;
		this.updatedAt = Instant.now();
	}

	public void addTask(PlannedTaskEntity task) {
		tasks.add(task);
		task.setPlan(this);
	}

}
