package com.aicompany.agentharness.harness;

import java.nio.file.Path;
import java.util.Objects;

public record Target(Path path) {

	public Target {
		Objects.requireNonNull(path, "path must not be null");
	}

}
