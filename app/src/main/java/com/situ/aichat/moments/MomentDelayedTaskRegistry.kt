package com.situ.aichat.moments

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 1:1 port of iOS `DelayedTaskRegistry` (Services/DelayedTaskRegistry.swift) for the 朋友圈 module.
 *
 * Tracks the in-process delayed coroutine jobs that drive AI auto-interaction (post→comment/like) and
 * AI replies, keyed by `(ownerUuid, purpose)` where `ownerUuid` = the post's uuid. As in iOS:
 * - [register] cancels any existing job under the same key first (natural dedup),
 * - [cancelAll] cancels every job for a post (called when the user deletes that post),
 * - [complete] uses a per-registration token so a job that finishes can't evict a newer one that
 *   replaced it (race guard).
 *
 * Why an `object` with its own [scope] rather than structured/`viewModelScope` coroutines: the jobs
 * outlive the WorkManager `doWork()` that schedules them and run while the app is foregrounded —
 * exactly the lifetime of iOS's untracked `Task { }`. Cancellation still works because the job
 * handle is held here, so this is NOT the `GlobalScope` anti-pattern the spec warns against (the
 * cancel signal reaches the job via the registry). When the process is killed mid-delay, the lost
 * products are rebuilt by foreground recovery (7.2.5) — the key resilience invariant (spec §4).
 *
 * The [Purpose] uuid String (not a DB id) avoids the iOS `_InvalidFutureBackingData` class of bug:
 * cross-coroutine work always re-fetches by business uuid (spec §4.3).
 */
object MomentDelayedTaskRegistry {

    /** Delayed-task kind. Reply carries the user-comment uuid so two replies under one post coexist. */
    sealed interface Purpose {
        /** A post's auto-interaction pass (like/comment by other characters). */
        data object AutoInteraction : Purpose
        /** An AI reply to a specific user comment. */
        data class Reply(val commentUuid: String) : Purpose
    }

    private data class Key(val ownerUuid: String, val purpose: Purpose)
    private data class Entry(val token: String, val job: Job)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tasks = ConcurrentHashMap<Key, Entry>()

    /**
     * Schedule [block] under `(ownerUuid, purpose)`, cancelling any prior job for the same key first
     * (iOS `register` cancels the old task → dedup). [block] is expected to `delay(...)` then do the
     * work; its cancellation propagates normally (a cancelled job just stops).
     */
    fun register(ownerUuid: String, purpose: Purpose, block: suspend () -> Unit) {
        val key = Key(ownerUuid, purpose)
        val token = UUID.randomUUID().toString()
        // Build lazily so we can store the entry (and cancel the old) before the job runs — no race
        // with [complete] firing before the map is updated.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "delayed task failed owner=${ownerUuid.take(6)}", e)
            } finally {
                complete(key, token)
            }
        }
        tasks.put(key, Entry(token, job))?.job?.cancel()
        job.start()
    }

    /** Cancel + drop a single delayed task. */
    fun cancel(ownerUuid: String, purpose: Purpose) {
        tasks.remove(Key(ownerUuid, purpose))?.job?.cancel()
    }

    /** Cancel + drop every delayed task for a post (iOS `cancelAll(for:)`, called on post delete). */
    fun cancelAll(ownerUuid: String) {
        val keys = tasks.keys.filter { it.ownerUuid == ownerUuid }
        for (key in keys) tasks.remove(key)?.job?.cancel()
    }

    /** Whether a delayed task is currently registered (foreground-recovery dedup, 7.2.5). */
    fun containsTask(ownerUuid: String, purpose: Purpose): Boolean =
        tasks.containsKey(Key(ownerUuid, purpose))

    /** Drop the entry on normal completion, but only if it's still the one we registered (token guard). */
    private fun complete(key: Key, token: String) {
        if (tasks[key]?.token == token) tasks.remove(key)
    }

    private const val TAG = "MomentDelayedTask"
}
