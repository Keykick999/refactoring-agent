package com.aicompany.agentharness.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record PlanIdentity(String targetPath, String objective, String fingerprint) {

	public static PlanIdentity of(AnalysisRequest request) {
		String fingerprint = request.style().name() + "\n" + request.enforcementMode().name() + "\nplan-v1";
		return new PlanIdentity(request.target().toAbsolutePath().normalize().toString(), request.objective(), fingerprint);
	}

	public String lockName() {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(("plan\n" + targetPath + "\n" + objective + "\n" + fingerprint).getBytes(StandardCharsets.UTF_8));
			return "plan-" + HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

}
