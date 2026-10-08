package com.aicompany.agentharness.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionPolicyTest {

	private static final List<ToolDefinition> REGISTERED_TOOLS = List.of(
			toolDefinition("read_text_file"),
			toolDefinition("list_directory"),
			toolDefinition("write_file"),
			toolDefinition("edit_file"));

	@Test
	void allowsToolInAllowedList() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of("read_text_file"), List.of());

		assertThat(policy.isAllowed("read_text_file")).isTrue();
	}

	@Test
	void deniesToolInDeniedList() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of("read_text_file"), List.of("write_file"));

		assertThat(policy.isAllowed("write_file")).isFalse();
	}

	@Test
	void deniedTakesPrecedenceOverAllowed() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of("read_text_file", "write_file"), List.of("write_file"));

		assertThat(policy.isAllowed("read_text_file")).isTrue();
		assertThat(policy.isAllowed("write_file")).isFalse();
	}

	@Test
	void deniesToolNotInAllowedList() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of("read_text_file"), List.of());

		assertThat(policy.isAllowed("list_directory")).isFalse();
	}

	@Test
	void allowsNoToolWhenAllowedListIsEmpty() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of(), List.of());

		assertThat(policy.isAllowed("read_text_file")).isFalse();
		assertThat(policy.allowedToolDefinitions(REGISTERED_TOOLS)).isEmpty();
	}

	@Test
	void excludesToolsNotInAllowedListFromExposedDefinitions() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(List.of("read_text_file", "list_directory"), List.of());

		assertThat(policy.allowedToolDefinitions(REGISTERED_TOOLS))
				.extracting(ToolDefinition::name)
				.containsExactly("read_text_file", "list_directory");
	}

	@Test
	void excludesDeniedToolsFromExposedDefinitions() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(
				List.of("read_text_file", "write_file", "edit_file"), List.of("write_file", "edit_file"));

		assertThat(policy.allowedToolDefinitions(REGISTERED_TOOLS))
				.extracting(ToolDefinition::name)
				.containsExactly("read_text_file");
	}

	@Test
	void ignoresConfiguredToolNamesThatAreNotRegistered() {
		ToolPermissionPolicy policy = new ToolPermissionPolicy(
				List.of("read_text_file", "not_existing_tool"), List.of("write_file", "removed_tool"));

		assertThat(policy.allowedToolDefinitions(REGISTERED_TOOLS))
				.extracting(ToolDefinition::name)
				.containsExactly("read_text_file");
		assertThat(policy.unknownToolNames(List.of("read_text_file", "list_directory", "write_file", "edit_file")))
				.containsExactly("not_existing_tool", "removed_tool");
	}

	private static ToolDefinition toolDefinition(String name) {
		return new ToolDefinition(name, "description of " + name, "{\"type\":\"object\"}");
	}

}
