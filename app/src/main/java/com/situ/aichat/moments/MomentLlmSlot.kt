package com.situ.aichat.moments

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 port of iOS `MomentGenerationService.interactionSemaphore` (+ `tryAcquireLLMSlot` /
 * `releaseLLMSlot`, Services/MomentGenerationService+Interaction.swift:20-37).
 *
 * Caps concurrent **LLM calls** during 朋友圈 auto-interaction at [MAX_CONCURRENT] = 2, so a burst of
 * posts can't fan out into an API-request storm. Crucially it guards only the LLM call (not the whole
 * interaction pass, which can run 10+ minutes) — so a user rapidly posting a 3rd moment isn't rejected
 * while two comment chains sleep.
 *
 * A global app-wide singleton shared across every interaction coroutine. Implemented as an
 * `AtomicInteger` CAS counter (not a kotlinx `Semaphore`) to mirror iOS's `OSAllocatedUnfairLock`
 * counter exactly, including the `count > 0` release guard that makes a stray double-release a no-op
 * rather than a crash.
 */
@Singleton
class MomentLlmSlot @Inject constructor() {

    private val count = AtomicInteger(0)

    /** Try to take a slot; returns false when [MAX_CONCURRENT] are already in use (caller skips). */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= MAX_CONCURRENT) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }

    /** Release a slot. Guarded so an over-release (future double-call) silently no-ops, never < 0. */
    fun release() {
        while (true) {
            val current = count.get()
            if (current <= 0) return
            if (count.compareAndSet(current, current - 1)) return
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 2
    }
}
