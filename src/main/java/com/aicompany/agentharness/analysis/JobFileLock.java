package com.aicompany.agentharness.analysis;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 같은 분석 작업이 한 프로세스에서만 실행되도록 운영체제 파일 잠금을 사용한다.
 * 프로세스가 끝나면 잠금은 풀린다.
 */
public final class JobFileLock implements AutoCloseable {

	private final FileChannel channel;
	private final FileLock lock;

	private JobFileLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	public static JobFileLock acquire(Path directory, String lockName) {
		try {
			Files.createDirectories(directory);
			FileChannel channel = FileChannel.open(directory.resolve(lockName + ".lock"),
					StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try {
				FileLock lock = channel.tryLock();
				if (lock == null) {
					channel.close();
					throw alreadyRunning();
				}
				return new JobFileLock(channel, lock);
			}
			catch (OverlappingFileLockException exception) {
				channel.close();
				throw alreadyRunning();
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not lock the analysis job", exception);
		}
	}

	private static JobAlreadyRunningException alreadyRunning() {
		return new JobAlreadyRunningException("This analysis job is already running.");
	}

	@Override
	public void close() {
		try {
			lock.release();
			channel.close();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not release the analysis job lock", exception);
		}
	}

}
