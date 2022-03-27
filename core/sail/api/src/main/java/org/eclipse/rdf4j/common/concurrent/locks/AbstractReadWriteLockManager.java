/*******************************************************************************
 * Copyright (c) 2022 Eclipse RDF4J contributors.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Distribution License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/org/documents/edl-v10.php.
 ******************************************************************************/

package org.eclipse.rdf4j.common.concurrent.locks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.rdf4j.common.concurrent.locks.diagnostics.LockCleaner;
import org.eclipse.rdf4j.common.concurrent.locks.diagnostics.LockDiagnostics;
import org.eclipse.rdf4j.common.concurrent.locks.diagnostics.LockMonitoring;
import org.eclipse.rdf4j.common.concurrent.locks.diagnostics.LockTracking;
import org.slf4j.LoggerFactory;

/**
 * An abstract base implementation of a read/write-lock manager.
 *
 * @author Håvard M. Ottestad
 */
public abstract class AbstractReadWriteLockManager implements ReadWriteLockManager {

	private final LockMonitoring readLockMonitoring;
	private final LockMonitoring writeLockMonitoring;

	// StampedLock for handling writers.
//	final StampedLock stampedLock = new StampedLock();

	volatile boolean writeLocked;
	private final AtomicBoolean writeLock = new AtomicBoolean();

	// LongAdder for handling readers. When the count is equal then there are no active readers.
	final LongAdder readersLocked = new LongAdder();
	final LongAdder readersUnlocked = new LongAdder();

	// milliseconds to wait when calling the try-lock method of the stamped lock
	private final int tryWriteLockMillis;

	/**
	 * When acquiring a write-lock, the thread will acquire the write-lock and then spin & yield while waiting for
	 * readers to unlock their locks. A deadlock is possible if someone already holding a read-lock acquires another
	 * read-lock at the same time that another thread is waiting for a write-lock. To stop this from happening we can
	 * set READ_PREFERENCE to a number higher than zero. READ_PREFERENCE of 1 means that the thread acquiring a
	 * write-lock will release the write-lock if there are any readers. A READ_PREFERENCE of 100 means that the thread
	 * acquiring a write-lock will spin & yield 100 times before it attempts to release the write-lock.
	 */
	final int writePreference;

	public AbstractReadWriteLockManager() {
		this(false);
	}

	public AbstractReadWriteLockManager(boolean trackLocks) {
		this(trackLocks, LockMonitoring.INITIAL_WAIT_TO_COLLECT);
	}

	public AbstractReadWriteLockManager(boolean trackLocks, int waitToCollect) {
		this("", waitToCollect, LockDiagnostics.fromLegacyTracking(trackLocks));
	}

	public AbstractReadWriteLockManager(String alias, LockDiagnostics... lockDiagnostics) {
		this(alias, LockMonitoring.INITIAL_WAIT_TO_COLLECT, lockDiagnostics);
	}

	public AbstractReadWriteLockManager(String alias, int waitToCollect, LockDiagnostics... lockDiagnostics) {

		this.tryWriteLockMillis = Math.min(1000, waitToCollect);

		// WRITE_PREFERENCE can not be negative or 0.
		this.writePreference = Math.max(1, getWriterPreference());

		boolean releaseAbandoned = false;
		boolean detectStalledOrDeadlock = false;
		boolean stackTrace = false;

		for (LockDiagnostics lockDiagnostic : lockDiagnostics) {
			switch (lockDiagnostic) {
			case releaseAbandoned:
				releaseAbandoned = true;
				break;
			case detectStalledOrDeadlock:
				detectStalledOrDeadlock = true;
				break;
			case stackTrace:
				stackTrace = true;
				break;
			}
		}

		if (lockDiagnostics.length == 0) {

			readLockMonitoring = LockMonitoring
					.wrap(Lock.ExtendedSupplier.wrap(this::createReadLockInner, this::tryReadLockInner));
			writeLockMonitoring = LockMonitoring
					.wrap(Lock.ExtendedSupplier.wrap(this::createWriteLockInner, this::tryWriteLockInner));

		} else if (releaseAbandoned && !detectStalledOrDeadlock) {

			readLockMonitoring = new LockCleaner(
					stackTrace,
					alias + "_READ",
					LoggerFactory.getLogger(this.getClass()),
					Lock.ExtendedSupplier.wrap(this::createReadLockInner, this::tryReadLockInner)
			);

			writeLockMonitoring = new LockCleaner(
					stackTrace,
					alias + "_WRITE",
					LoggerFactory.getLogger(this.getClass()),
					Lock.ExtendedSupplier.wrap(this::createWriteLockInner, this::tryWriteLockInner)
			);

		} else {

			readLockMonitoring = new LockTracking(
					stackTrace,
					alias + "_READ",
					LoggerFactory.getLogger(this.getClass()),
					waitToCollect,
					Lock.ExtendedSupplier.wrap(this::createReadLockInner, this::tryReadLockInner)
			);

			writeLockMonitoring = new LockTracking(
					stackTrace,
					alias + "_WRITE",
					LoggerFactory.getLogger(this.getClass()),
					waitToCollect,
					Lock.ExtendedSupplier.wrap(this::createWriteLockInner, this::tryWriteLockInner)
			);
		}
	}

	abstract int getWriterPreference();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isWriterActive() {
		return writeLocked;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isReaderActive() {
		long unlockedSum = readersUnlocked.sum();
		long lockedSum = readersLocked.sum();
		return unlockedSum != lockedSum;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void waitForActiveWriter() throws InterruptedException {
		while (writeLocked && !isReaderActive()) {
			spinWait();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void waitForActiveReaders() throws InterruptedException {
		while (isReaderActive()) {
			spinWait();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Lock getReadLock() throws InterruptedException {
		return readLockMonitoring.getLock();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean lockReadLock() throws InterruptedException {
		readersLocked.increment();

		while (writeLocked) {

			spinWaitAtReadLock();

			if (Thread.interrupted()) {
				readersUnlocked.increment();
				throw new InterruptedException();
			}
		}

		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void unlockReadLock(boolean locked) {
		if (locked) {
			readersUnlocked.increment();
		} else {
			assert false;
		}
	}

	Lock createReadLockInner() throws InterruptedException {
		lockReadLock();
		return new ReadLock(readersUnlocked);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Lock getWriteLock() throws InterruptedException {
		return writeLockMonitoring.getLock();
	}

	private Lock createWriteLockInner() throws InterruptedException {

		// Acquire a write-lock.
		boolean writeLocked = writeLockInterruptibly();
		if (!writeLocked) {
			throw new IllegalMonitorStateException("Excepted write lock to succeed");
		}
		boolean lockAcquired = false;

		try {
			int attempts = 0;

			// Wait for active readers to finish.
			do {

				if (Thread.interrupted()) {
					throw new InterruptedException();
				}

				// The order is important here.
				long unlockedSum = readersUnlocked.sum();
				long lockedSum = readersLocked.sum();
				if (unlockedSum == lockedSum) {
					// No active readers.
					lockAcquired = true;
				} else {

					if (unlockedSum > lockedSum) {
						// due to unlockReadLock(boolean) having been called more times than lockReadLock()
						throw new IllegalMonitorStateException(
								"Read lock was been released more times than it has been acquired!");
					}

					// If a thread is allowed to acquire more than one read-lock then we could deadlock if we keep
					// holding the write-lock while we wait for all readers to finish. This is because no read-locks can
					// be acquired while the write-lock is locked.
					if (attempts++ > writePreference) {
						attempts = 0;

						unlockWrite(writeLocked);
						writeLocked = false;

						yieldWait();

						writeLocked = writeLockInterruptibly();
					} else {
						spinWait();
					}

				}

			} while (!lockAcquired);
		} finally {
			if (!lockAcquired && writeLocked) {
				unlockWrite(writeLocked);
				writeLocked = false;
			}
		}

		return new WriteLock(this, writeLocked);
	}

	void unlockWrite(boolean writeLocked) {
		assert writeLocked;
		assert this.writeLocked;
		assert writeLock.get();

		this.writeLocked = false;
		writeLock.set(false);
	}

	private boolean writeLockInterruptibly() throws InterruptedException {

		boolean writeLocked = false;
		do {
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}

			writeLocked = writeLock();

			if (!writeLocked) {
				Thread.onSpinWait();
				if (writeLockMonitoring.requiresManualCleanup()) {
					writeLockMonitoring.runCleanup();
					readLockMonitoring.runCleanup();
				}
			}

		} while (!writeLocked);

		return writeLocked;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Lock tryReadLock() {
		return readLockMonitoring.tryLock();
	}

	private Lock tryReadLockInner() {
		readersLocked.increment();
		if (!writeLocked) {
			// Everything is good! We have acquired a read-lock and there are no active writers.
			return new ReadLock(readersUnlocked);
		} else {
			// There are active writers release our read lock
			readersUnlocked.increment();

			readLockMonitoring.runCleanup();
			writeLockMonitoring.runCleanup();
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Lock tryWriteLock() {
		return writeLockMonitoring.tryLock();
	}

	private Lock tryWriteLockInner() {
		// Try to acquire a write-lock.
		boolean writeLock = writeLock();

		if (writeLock) {

			// The order is important here.
			long unlockedSum = readersUnlocked.sum();
			long lockedSum = readersLocked.sum();
			if (unlockedSum == lockedSum) {
				// No active readers.
				return new WriteLock(this, writeLock);
			} else {
				unlockWrite(writeLocked);

				readLockMonitoring.runCleanup();
				writeLockMonitoring.runCleanup();
			}
		}

		return null;
	}

	private boolean writeLock() {
		boolean success = writeLock.compareAndSet(false, true);
		if (success) {
			writeLocked = true;
			return true;
		}
		return false;

	}

	void spinWait() throws InterruptedException {
		Thread.onSpinWait();

		writeLockMonitoring.runCleanup();
		readLockMonitoring.runCleanup();

		if (Thread.interrupted()) {
			throw new InterruptedException();
		}

	}

	void spinWaitAtReadLock() {
		Thread.onSpinWait();

		writeLockMonitoring.runCleanup();

	}

	private void yieldWait() throws InterruptedException {
		Thread.yield();

		writeLockMonitoring.runCleanup();
		readLockMonitoring.runCleanup();

		if (Thread.interrupted()) {
			throw new InterruptedException();
		}

	}

}

class WriteLock implements Lock {

	private final AbstractReadWriteLockManager lock;
	private boolean writeLocked;

	public WriteLock(AbstractReadWriteLockManager lock, boolean writeLocked) {
		this.writeLocked = writeLocked;
		assert writeLocked;

		this.lock = lock;
	}

	@Override
	public boolean isActive() {
		return writeLocked;
	}

	@Override
	public void release() {
		boolean tempWriteLocked = writeLocked;
		writeLocked = false;

		if (!tempWriteLocked) {
			throw new IllegalMonitorStateException("Trying to release a lock that is not locked");
		}

		lock.unlockWrite(tempWriteLocked);
	}
}

class ReadLock implements Lock {

	private LongAdder readersUnlocked;

	public ReadLock(LongAdder readersUnlocked) {
		this.readersUnlocked = readersUnlocked;
	}

	@Override
	public boolean isActive() {
		return readersUnlocked != null;
	}

	@Override
	public void release() {
		if (readersUnlocked == null) {
			throw new IllegalMonitorStateException("Trying to release a lock that is not locked");
		}

		readersUnlocked.increment();
		readersUnlocked = null;
	}
}
