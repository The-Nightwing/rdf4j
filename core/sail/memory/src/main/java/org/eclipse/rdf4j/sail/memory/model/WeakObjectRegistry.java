/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.memory.model;

import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An object registry that uses weak references to keep track of the stored objects. The registry can be used to
 * retrieve stored objects using another, equivalent object. As such, it can be used to prevent the use of duplicates in
 * another data structure, reducing memory usage. The objects that are being stored should properly implement the
 * {@link Object#equals} and {@link Object#hashCode} methods.
 */
public class WeakObjectRegistry<E> extends AbstractSet<E> {

	private static final Logger logger = LoggerFactory.getLogger(WeakObjectRegistry.class);

	/*-----------*
	 * Variables *
	 *-----------*/

	/**
	 * The hash map that is used to store the objects.
	 */
	private final Map<E, WeakReference<E>>[] objectMap;
	private final AdderBasedReadWriteLock[] locks;

	/*--------------*
	 * Constructors *
	 *--------------*/

	/**
	 * Constructs a new, empty object registry.
	 */
	public WeakObjectRegistry() {
		super();
		int concurrency = Runtime.getRuntime().availableProcessors() * 2;

		objectMap = new WeakHashMap[concurrency];
		for (int i = 0; i < objectMap.length; i++) {
			objectMap[i] = new WeakHashMap<>();
		}

		locks = new AdderBasedReadWriteLock[objectMap.length];
		for (int index = 0; index < locks.length; index++) {
			locks[index] = new AdderBasedReadWriteLock();
		}
	}

	/**
	 * Constructs a new WeakObjectRegistry containing the elements in the specified collection.
	 *
	 * @param c The collection whose elements are to be placed into this object registry.
	 * @throws NullPointerException If the specified collection is null.
	 */
	public WeakObjectRegistry(Collection<? extends E> c) {
		this();
		addAll(c);
	}

	/*---------*
	 * Methods *
	 *---------*/

	/**
	 * Retrieves the stored object that is equal to the supplied <var>key</var> object.
	 *
	 * @param key The object that should be used as the search key for the operation.
	 * @return A stored object that is equal to the supplied key, or <var>null</var> if no such object was found.
	 */
	public E get(Object key) {
		if (key == null) {
			return null;
		}

		int index = getIndex(key);
		boolean readLock = locks[index].readLock();
		try {
			Map<E, WeakReference<E>> weakReferenceMap = objectMap[index];

			WeakReference<E> weakRef = weakReferenceMap.get(key);
			if (weakRef != null) {
				return weakRef.get(); // may be null
			} else {
				return null;
			}

		} finally {
			locks[index].unlockReader(readLock);
		}

	}

	private int getIndex(Object key) {
		int i = Math.abs(key.hashCode());
		return i % objectMap.length;
	}

	public AutoCloseableIterator<E> closeableIterator() {
		return new AutoCloseableIterator<>(objectMap, locks);
	}

	@Override
	public Iterator<E> iterator() {
		logger.warn("This method is not thread safe! Use closeableIterator() instead.");
		return new AutoCloseableIterator<E>(objectMap, null);
	}

	public static class AutoCloseableIterator<E> implements Iterator<E>, AutoCloseable {

		private final Iterator<Map<E, WeakReference<E>>> iterator;
		private final AdderBasedReadWriteLock[] locks;

		Iterator<E> currentIterator;
		boolean[] readLocks;
		boolean init = false;

		public AutoCloseableIterator(Map<E, WeakReference<E>>[] objectMap, AdderBasedReadWriteLock[] locks) {
			this.iterator = Arrays.asList(objectMap).iterator();
			this.locks = locks;
		}

		public void init() {
			if (!init) {
				init = true;
				if (locks != null) {
					readLocks = new boolean[locks.length];
					for (int index = 0; index < locks.length; index++) {
						readLocks[index] = locks[index].readLock();
					}
				}
				currentIterator = iterator.next().keySet().iterator();
			}
		}

		@Override
		public boolean hasNext() {
			init();
			if (currentIterator == null) {
				return false;
			}
			while (currentIterator != null) {
				if (currentIterator.hasNext()) {
					return true;
				} else {
					currentIterator = null;
					if (iterator.hasNext()) {
						currentIterator = iterator.next().keySet().iterator();
					}
				}
			}

			return false;
		}

		@Override
		public E next() {
			init();
			return currentIterator.next();
		}

		@Override
		public void close() {
			if (init) {
				if (locks != null) {
					for (int index = 0; index < locks.length; index++) {
						if (readLocks[index]) {
							locks[index].unlockReader(readLocks[index]);
							readLocks[index] = false;
						}
					}
				}
			}
		}

	}

	@Override
	public int size() {
		int size = 0;
		for (Map<E, WeakReference<E>> weakReferenceMap : objectMap) {
			size += weakReferenceMap.size();
		}
		return size;
	}

	@Override
	public boolean contains(Object key) {
		return get(key) != null;
	}

	@Override
	public boolean add(E object) {
		int index = getIndex(object);
		long writeLock = locks[index].writeLock();
		try {
			Map<E, WeakReference<E>> weakReferenceMap = objectMap[index];
			WeakReference<E> ref = new WeakReference<>(object);

			ref = weakReferenceMap.put(object, ref);

			if (ref != null) {
				E e = ref.get();
				if (e != null) {
					// A duplicate was added which replaced the existing object. Undo this operation.
					weakReferenceMap.put(e, ref);
					return false;
				}
			}

			return true;

		} finally {
			locks[index].unlockWriter(writeLock);
		}

	}

	public E getOrAdd(Object key, Supplier<E> supplier) {
		int index = getIndex(key);
		Map<E, WeakReference<E>> weakReferenceMap = objectMap[index];

		boolean readLock = locks[index].readLock();
		try {
			WeakReference<E> ref = weakReferenceMap.get(key);
			if (ref != null) {
				E e = ref.get();
				if (e != null) {
					// we found the object
					return e;
				}
			}
		} finally {
			locks[index].unlockReader(readLock);
		}

		// we could not find the object, so we will use the supplier to create a new object and add that
		long writeLock = locks[index].writeLock();
		try {
			E object = supplier.get();
			WeakReference<E> ref = weakReferenceMap.put(object, new WeakReference<>(object));
			if (ref != null) {
				E e = ref.get();
				if (e != null) {
					// Between releasing the read-lock and acquiring the write-lock another thread put the object in the
					// weakReferenceMap. We need to put back the one that was there before and return that one to the
					// user.
					weakReferenceMap.put(e, ref);
					return e;
				}
			}
			assert object != null;
			return object;

		} finally {
			locks[index].unlockWriter(writeLock);
		}

	}

	@Override
	public boolean remove(Object object) {
		int index = getIndex(object);
		long writeLock = locks[index].writeLock();
		try {
			Map<E, WeakReference<E>> weakReferenceMap = objectMap[index];
			WeakReference<E> ref = weakReferenceMap.remove(object);
			return ref != null && ref.get() != null;

		} finally {
			locks[index].unlockWriter(writeLock);
		}
	}

	@Override
	public void clear() {

		for (int index = 0; index < objectMap.length; index++) {
			long writeLock = locks[index].writeLock();
			try {
				objectMap[index].clear();
			} finally {
				locks[index].unlockWriter(writeLock);
			}
		}

	}

	private static class AdderBasedReadWriteLock {

		// StampedLock for handling writers.
		private final StampedLock lock = new StampedLock();

		// LongAdder for handling readers. When the count is equal then there are no active readers.
		private final LongAdder readersLocked = new LongAdder();
		private final LongAdder readersUnlocked = new LongAdder();

		public boolean readLock() {
			while (true) {
				readersLocked.increment();
				if (!lock.isWriteLocked()) {
					// Everything is good! We have acquired a read-lock and there are no active writers.
					return true;
				} else {
					// Release our read lock so we don't block any writers.
					readersUnlocked.increment();
					while (lock.isWriteLocked()) {
						Thread.onSpinWait();
					}
				}
			}
		}

		public void unlockReader(boolean locked) {
			if (locked) {
				readersUnlocked.increment();
			} else {
				throw new IllegalMonitorStateException();
			}
		}

		public long writeLock() {
			// Acquire a write-lock.
			long writeStamp = lock.writeLock();

			// Wait for active readers to finish.
			while (true) {
				// The order is important here.
				long unlockedSum = readersUnlocked.sum();
				long lockedSum = readersLocked.sum();
				if (unlockedSum == lockedSum) {
					// No active readers.
					return writeStamp;
				} else {
					Thread.onSpinWait();
				}

			}
		}

		public void unlockWriter(long stamp) {
			if (stamp != 0) {
				// Make sure that readers in other threads will be able to read the writes that were made by the user
				// within the write-locked section. The stamped lock only guarantees that writes are visible to other
				// threads if those threads use a stamped lock read-lock.
				VarHandle.fullFence();
				lock.unlockWrite(stamp);
			} else {
				throw new IllegalMonitorStateException();
			}
		}

	}

}
