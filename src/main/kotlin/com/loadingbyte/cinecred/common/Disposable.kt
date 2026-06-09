package com.loadingbyte.cinecred.common

import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class SizedValue<V : Any>(val value: V, val bytes: Long, val destroy: Runnable? = null)


class DisposableReference<V : Any>(sizedValue: SizedValue<V>) : AutoCloseable {

    private val trackerKey = Any()
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKey))

    init {
        DisposableTracker.put(trackerKey, sizedValue)
    }

    constructor(value: V, bytes: Long, destroy: Runnable? = null) : this(SizedValue(value, bytes, destroy))

    @Suppress("UNCHECKED_CAST")
    fun get(): V? =
        DisposableTracker.get(trackerKey)?.get() as V?

    /** Retrieves the value, clears the reference, and prevents the value from being auto-destroyed in the future. */
    @Suppress("UNCHECKED_CAST")
    fun plunder(): V? =
        DisposableTracker.remove(trackerKey, destroy = false)?.get() as V?

    override fun close() {
        cleanable.clean()
    }

    fun getAndClose(): V? =
        get().also { close() }

    private class CleanerAction(private val trackerKey: Any) : Runnable {
        override fun run() {
            DisposableTracker.remove(trackerKey, destroy = true)
        }
    }

}


class DisposableCache<K : Any, V : Any> : AutoCloseable {

    private val cacheId = Any()
    // The key set is only accessed or modified when the MemoryTracker lock is held, hence we don't need more locking.
    private val trackerKeys = Collections.newSetFromMap(WeakHashMap<TrackerKey, Boolean>())
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKeys))
    @Volatile private var closed = false

    @Suppress("UNCHECKED_CAST")
    fun getAsync(key: K): CompletableFuture<V>? =
        DisposableTracker.get(TrackerKey(cacheId, key)) as CompletableFuture<V>?

    /** @throws IllegalStateException If the cache is closed. */
    inline fun get(key: K, crossinline compute: () -> SizedValue<V>): V =
        getAsync(key) { CompletableFuture.completedFuture(compute()) }.get()

    /** @return A future that fails with an [IllegalStateException] if the cache is closed. */
    fun getAsync(key: K, compute: () -> CompletableFuture<SizedValue<V>>): CompletableFuture<V> {
        val trackerKey = TrackerKey(cacheId, key)
        var computeFuture: CompletableFuture<SizedValue<*>>? = null
        val getFuture = DisposableTracker.cache(trackerKey) {
            if (closed)
                return CompletableFuture.failedFuture(IllegalStateException("The disposable cache is already closed."))
            trackerKeys += trackerKey
            CompletableFuture<SizedValue<*>>().also { computeFuture = it }
        }
        // Run the user-provided compute function outside the cache lambda to not block the lock for too long.
        if (computeFuture != null)
            compute().whenComplete { v, t ->
                if (t != null) computeFuture.completeExceptionally(t) else computeFuture.complete(v)
            }
        @Suppress("UNCHECKED_CAST")
        return getFuture as CompletableFuture<V>
    }

    fun getAll(): List<V> =
        getAllAsync().map(CompletableFuture<V>::get)

    @Suppress("UNCHECKED_CAST")
    fun getAllAsync(): List<CompletableFuture<V>> =
        DisposableTracker.getAll(trackerKeys) as List<CompletableFuture<V>>

    override fun close() {
        closed = true
        cleanable.clean()
    }

    private data class TrackerKey(private val cacheId: Any, private val cacheKey: Any)

    private class CleanerAction(private val trackerKeys: Iterable<TrackerKey>) : Runnable {
        override fun run() {
            DisposableTracker.removeAll(trackerKeys)
        }
    }

}


fun disposableBytes(): Long = DisposableTracker.bytes()


private object DisposableTracker {

    // We want to limit memory-tracked objects to use at most 20% of the available memory.
    private val maxBytes = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
        .totalMemorySize / 5

    private val lock = ReentrantLock()
    private val map = LinkedHashMap<Any, CompletableFuture<SizedValue<*>>>(16, 0.75f, true)
    private var curBytes = 0L

    fun bytes(): Long =
        lock.withLock { curBytes }

    fun get(key: Any): CompletableFuture<*>? =
        lock.withLock {
            map[key]
        }?.thenApply(SizedValue<*>::value)

    fun getAll(keys: Iterable<Any>): List<CompletableFuture<*>> {
        return lock.withLock {
            keys.mapNotNull { key -> map[key]?.thenApply(SizedValue<*>::value) }
        }
    }

    fun put(key: Any, sv: SizedValue<*>) {
        lock.withLock {
            if (map.put(key, CompletableFuture.completedFuture(sv)) != null)
                throw UnsupportedOperationException("Cannot override previous mappings.")
            curBytes += sv.bytes
            evictIfFull()
        }.forEach { sv -> sv.destroy?.run() }
    }

    inline fun cache(key: Any, compute: () -> CompletableFuture<SizedValue<*>>): CompletableFuture<*> {
        val future: CompletableFuture<SizedValue<*>>
        var evictedFuture: CompletableFuture<List<SizedValue<*>>>? = null
        lock.withLock {
            future = map[key] ?: compute().also { f ->
                map[key] = f
                evictedFuture = f.thenApply { sv ->
                    lock.withLock {
                        curBytes += sv.bytes
                        evictIfFull()
                    }
                }
            }
        }
        evictedFuture?.thenAccept { svs -> for (sv in svs) sv.destroy?.run() }
        return future.thenApply(SizedValue<*>::value)
    }

    fun remove(key: Any, destroy: Boolean): CompletableFuture<*>? =
        lock.withLock {
            map.remove(key)?.also { future -> future.thenAccept { sv -> lock.withLock { curBytes -= sv.bytes } } }
        }?.thenApply { sv -> if (destroy) sv.destroy?.run(); sv.value }

    fun removeAll(keys: Iterable<Any>) {
        lock.withLock {
            keys.mapNotNull { key ->
                map.remove(key)?.also { future -> future.thenAccept { sv -> lock.withLock { curBytes -= sv.bytes } } }
            }
        }.forEach { future -> future.thenAccept { sv -> sv.destroy?.run() } }
    }

    // Note: Must be called while holding the lock.
    private fun evictIfFull(): List<SizedValue<*>> {
        if (curBytes > maxBytes) {
            val evicted = mutableListOf<SizedValue<*>>()
            val iter = map.iterator()
            while (curBytes > maxBytes && iter.hasNext()) {
                val future = iter.next().value
                // Only evict completed futures. If there are actually pending futures to be evicted (highly unlikely),
                // that will be done at a later date once they're completed.
                if (future.isDone) {
                    val sv = future.get()
                    evicted += sv
                    curBytes -= sv.bytes
                    iter.remove()
                }
            }
            return evicted
        }
        return emptyList()
    }

}
