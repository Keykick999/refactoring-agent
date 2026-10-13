package com.aicompany.agentharness.graph;

/**
 * 관계가 발견된 코드 구조. 관계의 종류({@link RelationshipType})와는 별개다.
 */
public enum RelationshipSource {

	FIELD,
	CONSTRUCTOR_PARAMETER,
	METHOD_PARAMETER,
	RETURN_TYPE,
	EXTENDS,
	IMPLEMENTS

}
