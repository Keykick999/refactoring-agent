package com.aicompany.agentharness.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisPlanRepository extends JpaRepository<AnalysisPlanEntity, Long> {

	Optional<AnalysisPlanEntity> findByTargetPathAndObjectiveAndSettingsFingerprint(String targetPath, String objective,
			String settingsFingerprint);

}
