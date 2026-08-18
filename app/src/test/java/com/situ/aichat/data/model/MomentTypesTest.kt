package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Enum raw-value + fallback parity with iOS (`MomentAuthorType` / `MomentNotificationType`,
 * Models/MomentPost.swift + Models/MomentNotification.swift). Raw strings are persisted and must
 * match iOS exactly for backup round-trips; the fallback target mirrors iOS `?? .user` /
 * `?? .commentOnUserPost`.
 */
class MomentTypesTest {

    @Test fun `author type raw values match iOS`() {
        assertEquals("user", MomentAuthorType.USER.raw)
        assertEquals("character", MomentAuthorType.CHARACTER.raw)
    }

    @Test fun `author type parses known and falls back to user`() {
        assertEquals(MomentAuthorType.USER, MomentAuthorType.fromRaw("user"))
        assertEquals(MomentAuthorType.CHARACTER, MomentAuthorType.fromRaw("character"))
        assertEquals(MomentAuthorType.USER, MomentAuthorType.fromRaw("bogus"))
        assertEquals(MomentAuthorType.USER, MomentAuthorType.fromRaw(""))
    }

    @Test fun `notification type raw values match iOS`() {
        assertEquals("commentOnUserPost", MomentNotificationType.COMMENT_ON_USER_POST.raw)
        assertEquals("replyToUserComment", MomentNotificationType.REPLY_TO_USER_COMMENT.raw)
        assertEquals("likeOnUserPost", MomentNotificationType.LIKE_ON_USER_POST.raw)
        assertEquals("coLike", MomentNotificationType.CO_LIKE.raw)
    }

    @Test fun `notification type parses known and falls back to commentOnUserPost`() {
        assertEquals(MomentNotificationType.LIKE_ON_USER_POST, MomentNotificationType.fromRaw("likeOnUserPost"))
        assertEquals(MomentNotificationType.CO_LIKE, MomentNotificationType.fromRaw("coLike"))
        assertEquals(MomentNotificationType.COMMENT_ON_USER_POST, MomentNotificationType.fromRaw("commentOnUserPost"))
        assertEquals(MomentNotificationType.COMMENT_ON_USER_POST, MomentNotificationType.fromRaw("unknown"))
    }
}
