package com.loadingbyte.cinecred.common

import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class SizedValue<V : Any>(val value: V, val bytes: Long, val destroy: Runnable? = null)


class DisposableReference<V : Any>(sizedValue: SizedValue<V>) : AutoCloseable {

    private val trackerKey = Any()
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKey))
    private var value: V? = sizedValue.value

    init {
        DisposableTracker.put(trackerKey, Disposable(sizedValue, DisposeAction(WeakReference(this))))
    }

    constructor(value: V, bytes: Long, destroy: Runnable? = null) : this(SizedValue(value, bytes, destroy))

    fun get(): V? {
        DisposableTracker.keepAlive(trackerKey)
        return value
    }

    override fun close() {
        cleanable.clean()
    }

    fun getAndClose(): V? =
        get().also { close() }

    private class DisposeAction(private val ref: WeakReference<DisposableReference<*>>) : Runnable {
        override fun run() {
            ref.get()?.value = null
        }
    }

    private class CleanerAction(private val trackerKey: Any) : Runnable {
        override fun run() {
            DisposableTracker.remove(trackerKey)
        }
    }

}


class DisposableCache<K : Any, V : Any> : AutoCloseable {

    private val lock = ReentrantLock()
    private val cacheId = Any()
    // We have to separate "trackerKeys" from "futures" to avoid the cleaner holding a strong reference to the cached
    // values, which would lead to memory leaks if the cached values in turn hold a strong reference to the cache.
    // For example, this is the case for Font.Case.
    private val trackerKeys = HashSet<TrackerKey>()
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKeys))
    private val futures = HashMap<K, CompletableFuture<V>>()
    private var closed = false

    fun getAsync(key: K): CompletableFuture<V>? {
        DisposableTracker.keepAlive(TrackerKey(cacheId, key))
        return lock.withLock { futures[key] }
    }

    /** @throws IllegalStateException If the cache is closed. */
    inline fun get(key: K, crossinline compute: () -> SizedValue<V>): V =
        getAsync(key) { CompletableFuture.completedFuture(compute()) }.get()

    /** @return A future that fails with an [IllegalStateException] if the cache is closed. */
    fun getAsync(key: K, compute: () -> CompletableFuture<SizedValue<V>>): CompletableFuture<V> {
        val trackerKey = TrackerKey(cacheId, key)
        val newFuture: CompletableFuture<V>
        lock.withLock {
            if (closed)
                return CompletableFuture.failedFuture(IllegalStateException("The disposable cache is already closed."))
            futures[key]?.let { future ->
                DisposableTracker.keepAlive(trackerKey)
                return future
            }
            newFuture = CompletableFuture<V>()
            futures[key] = newFuture
            trackerKeys.add(trackerKey)
        }
        compute().whenComplete { v, t ->
            val isClosed = lock.withLock {
                if (!closed && t == null)
                    DisposableTracker.put(trackerKey, Disposable(v, DisposeAction(WeakReference(this), trackerKey)))
                closed
            }
            when {
                isClosed -> {
                    v?.destroy?.run()
                    newFuture.completeExceptionally(IllegalStateException("The disposable cache is already closed."))
                }
                t != null -> newFuture.completeExceptionally(t)
                else -> newFuture.complete(v.value)
            }
        }
        return newFuture
    }

    fun getAll(): List<V> =
        getAllAsync().map(CompletableFuture<V>::get)

    fun getAllAsync(): List<CompletableFuture<V>> =
        lock.withLock {
            DisposableTracker.keepAllAlive(trackerKeys)
            futures.values.toMutableList()
        }

    override fun close() {
        lock.withLock { closed = true }
        cleanable.clean()
    }

    private data class TrackerKey(private val cacheId: Any, val cacheKey: Any)

    private class DisposeAction(
        private val ref: WeakReference<DisposableCache<*, *>>,
        private val trackerKey: TrackerKey
    ) : Runnable {
        override fun run() {
            ref.get()?.let { cache ->
                cache.lock.withLock {
                    cache.futures.remove(trackerKey.cacheKey)
                    cache.trackerKeys.remove(trackerKey)
                }
            }
        }
    }

    private class CleanerAction(private val trackerKeys: Set<TrackerKey>) : Runnable {
        override fun run() {
            // It should be safe to use trackerKeys without a lock because the associated DisposableCache instance,
            // which is the only other user of the collection, is no longer reachable when this cleaner runs.
            DisposableTracker.removeAll(trackerKeys)
        }
    }

}


fun disposableBytes(): Long = DisposableTracker.bytes()


private class Disposable(sizedValue: SizedValue<*>, private val dispose: Runnable) {

    // Note: We must not store the entire SizedValue because that holds a reference to "value", which must never be
    // strongly reference by DisposableTracker in order to avoid garbage collection gotchas.
    val bytes = sizedValue.bytes
    private val destroy = sizedValue.destroy

    fun evict() {
        destroy?.run()
        dispose.run()
    }

}


private object DisposableTracker {

    // We want to limit memory-tracked objects to use at most 20% of the available memory.
    private val maxBytes = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
        .totalMemorySize / 5

    private val lock = ReentrantLock()
    private val map = LinkedHashMap<Any, Disposable>(16, 0.75f, true)
    private var curBytes = 0L

    fun bytes(): Long =
        lock.withLock { curBytes }

    fun keepAlive(key: Any) {
        lock.withLock { map[key] }
    }

    fun keepAllAlive(keys: Iterable<Any>) {
        lock.withLock { for (key in keys) map[key] }
    }

    fun put(key: Any, disposable: Disposable) {
        lock.withLock {
            if (map.put(key, disposable) != null)
                throw UnsupportedOperationException("Cannot override previous mappings.")
            curBytes += disposable.bytes
            if (curBytes > maxBytes) {
                val evicted = mutableListOf<Disposable>()
                val iter = map.iterator()
                while (curBytes > maxBytes && iter.hasNext()) {
                    val disposable = iter.next().value
                    evicted.add(disposable)
                    curBytes -= disposable.bytes
                    iter.remove()
                }
                evicted
            } else
                emptyList()
        }.forEach(Disposable::evict)
    }

    fun remove(key: Any) {
        lock.withLock {
            map.remove(key)?.also { disposable -> curBytes -= disposable.bytes }
        }?.evict()
    }

    fun removeAll(keys: Iterable<Any>) {
        lock.withLock {
            keys.mapNotNull { key ->
                map.remove(key)?.also { disposable -> curBytes -= disposable.bytes }
            }
        }.forEach(Disposable::evict)
    }

}
