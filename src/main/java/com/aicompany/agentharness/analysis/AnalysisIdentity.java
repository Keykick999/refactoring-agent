package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.harness.Task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 분석 대상 경로, 목표, 설정이 모두 같을 때만 같은 작업으로 본다.
 */
public record AnalysisIdentity(String targetPath, String objective, String settingsFingerprint) {

	public static AnalysisIdentity of(Task task, AnalysisSettings settings) {
		return new AnalysisIdentity(task.target().path().toAbsolutePath().normalize().toString(), task.goal(), settings.fingerprint());
	}

	public String lockName() {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest((targetPath + "\n" + objective + "\n" + settingsFingerprint).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

}
