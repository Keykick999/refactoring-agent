package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.JobStatus;
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
@Table(name = "analysis_job", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_job_identity",
		columnNames = {"target_path", "objective", "settings_fingerprint"}))
public class AnalysisJobEntity {

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
	private JobStatus status;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("filePath")
	private List<ClassAnalysisEntity> classes = new ArrayList<>();

	protected AnalysisJobEntity() {
	}

	public AnalysisJobEntity(String targetPath, String objective, String settingsFingerprint, Instant createdAt) {
		this.targetPath = targetPath;
		this.objective = objective;
		this.settingsFingerprint = settingsFingerprint;
		this.status = JobStatus.RUNNING;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public JobStatus getStatus() {
		return status;
	}

	public void setStatus(JobStatus status) {
		this.status = status;
		this.updatedAt = Instant.now();
	}

	public List<ClassAnalysisEntity> getClasses() {
		return classes;
	}

	public void addClass(ClassAnalysisEntity classAnalysis) {
		classes.add(classAnalysis);
		classAnalysis.setJob(this);
	}

}
