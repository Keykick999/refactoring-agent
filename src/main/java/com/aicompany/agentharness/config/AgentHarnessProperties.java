package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("agent-harness")
public record AgentHarnessProperties(int maxSteps, FilesystemMcpServer filesystemMcpServer, @DefaultValue Tools tools,
		@DefaultValue ClassAnalysis classAnalysis) {

	/**
	 * @param command   MCP Server 실행 명령. 허용 디렉터리(workspace)는 이 명령 뒤에 인자로 붙는다.
	 * @param workspace MCP Server가 접근을 허용할 유일한 디렉터리. 상대 경로는 실행 디렉터리 기준이다.
	 *                  Spring의 Path 변환은 상대 경로를 classpath 기준으로 해석하므로 문자열로 받는다.
	 */
	public record FilesystemMcpServer(boolean enabled, List<String> command, String workspace, Duration timeout) {

		public Path workspaceDirectory() {
			return Path.of(workspace).toAbsolutePath().normalize();
		}

	}

	/**
	 * @param allowed Agent가 사용할 수 있는 Tool 이름. 비어 있으면 어떤 Tool도 허용하지 않는다.
	 * @param denied  allowed에 있더라도 사용할 수 없는 Tool 이름.
	 */
	public record Tools(@DefaultValue List<String> allowed, @DefaultValue List<String> denied) {
	}

	/**
	 * 클래스 하나의 원문을 한 번의 LLM 요청으로 분석할 때의 설정.
	 *
	 * @param maxSourceCharacters 이 문자 수를 넘는 파일은 자르지 않고 분석하지 않는다.
	 * @param searchTool          디렉터리에서 Java 파일을 찾는 MCP Tool 이름
	 * @param readTool            파일 원문을 읽는 MCP Tool 이름
	 */
	public record ClassAnalysis(
			@DefaultValue("100000") int maxSourceCharacters,
			@DefaultValue("search_files") String searchTool,
			@DefaultValue("read_text_file") String readTool) {
	}

}
