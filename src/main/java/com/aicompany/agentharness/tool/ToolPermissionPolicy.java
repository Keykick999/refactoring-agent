package com.aicompany.agentharness.tool;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Agent가 사용할 수 있는 Tool을 allowed/denied 이름 목록으로 제한한다.
 *
 * <p>allowed에 있고 denied에 없는 Tool만 허용한다. allowed가 비어 있으면 어떤 Tool도 허용하지 않는다.
 * 허용 목록을 코드에 두면 특정 Tool 제공자의 Tool 이름에 묶이므로, 기본 허용 목록은 설정에서만 관리한다.
 */
public class ToolPermissionPolicy {

	private final Set<String> allowedToolNames;
	private final Set<String> deniedToolNames;

	public ToolPermissionPolicy(Collection<String> allowedToolNames, Collection<String> deniedToolNames) {
		this.allowedToolNames = Set.copyOf(allowedToolNames);
		this.deniedToolNames = Set.copyOf(deniedToolNames);
	}

	public boolean isAllowed(String toolName) {
		return allowedToolNames.contains(toolName) && !deniedToolNames.contains(toolName);
	}

	public List<ToolDefinition> allowedToolDefinitions(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream()
				.filter(toolDefinition -> isAllowed(toolDefinition.name()))
				.toList();
	}

	/**
	 * 등록된 Tool 중에 없는 설정 이름. 설정 오타나 서버 변경을 알리는 용도로만 쓰며, 정책 판단에는 영향이 없다.
	 */
	public List<String> unknownToolNames(Collection<String> registeredToolNames) {
		return Stream.concat(allowedToolNames.stream(), deniedToolNames.stream())
				.filter(toolName -> !registeredToolNames.contains(toolName))
				.distinct()
				.sorted()
				.toList();
	}

}
