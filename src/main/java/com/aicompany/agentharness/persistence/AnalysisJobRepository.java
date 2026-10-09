package com.aicompany.agentharness.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJobEntity, Long> {

	Optional<AnalysisJobEntity> findByTargetPathAndObjectiveAndSettingsFingerprint(String targetPath, String objective,
			String settingsFingerprint);

}
