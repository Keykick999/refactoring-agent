package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.AnalysisIdentity;
import com.aicompany.agentharness.analysis.AnalysisJobStore;
import com.aicompany.agentharness.analysis.AnalysisReport;
import com.aicompany.agentharness.analysis.AnalysisSession;
import com.aicompany.agentharness.analysis.ClassAnalysisResult;
import com.aicompany.agentharness.analysis.DiscoveredJavaSource;
import com.aicompany.agentharness.analysis.PendingClass;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link AnalysisPersistence}의 트랜잭션 메서드를 세션으로 연결한다.
 * 코디네이터가 이 클래스를 직접 트랜잭션으로 감싸지 않는다.
 */
@Service
public class JpaAnalysisJobStore implements AnalysisJobStore {

	private final AnalysisPersistence persistence;

	public JpaAnalysisJobStore(AnalysisPersistence persistence) {
		this.persistence = persistence;
	}

	@Override
	public AnalysisSession begin(AnalysisIdentity identity, List<DiscoveredJavaSource> sources) {
		return new PersistenceAnalysisSession(persistence, persistence.prepare(identity, sources));
	}

	private static final class PersistenceAnalysisSession implements AnalysisSession {

		private final AnalysisPersistence persistence;
		private final long jobId;
		private final List<PendingClass> pending;

		private PersistenceAnalysisSession(AnalysisPersistence persistence, PreparedAnalysis prepared) {
			this.persistence = persistence;
			this.jobId = prepared.jobId();
			this.pending = prepared.pending();
		}

		@Override
		public boolean hasWork() {
			return !pending.isEmpty();
		}

		@Override
		public List<PendingClass> pending() {
			return pending;
		}

		@Override
		public void markRunning(Path path) {
			persistence.markRunning(jobId, path.toAbsolutePath().normalize().toString());
		}

		@Override
		public void record(ClassAnalysisResult result) {
			persistence.saveResult(jobId, result);
		}

		@Override
		public AnalysisReport finish(boolean stoppedByAuthentication) {
			return persistence.finish(jobId, stoppedByAuthentication);
		}

		@Override
		public AnalysisReport report() {
			return persistence.report(jobId);
		}

	}

}
