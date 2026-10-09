package com.aicompany.agentharness.analysis;

import java.nio.file.Path;

/**
 * 이번 실행에서 분석해야 하는 클래스. 원문은 저장하지 않고 다시 읽는다.
 */
public record PendingClass(String className, Path path) {
}
