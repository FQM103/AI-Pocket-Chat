package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Round-trip + edge cases for the image-path-list column codec. */
class StringListJsonTest {

    @Test fun `empty list encodes to blank and decodes back to empty`() {
        assertEquals("", StringListJson.encode(emptyList()))
        assertEquals(emptyList<String>(), StringListJson.decode(""))
        assertEquals(emptyList<String>(), StringListJson.decode("   "))
    }

    @Test fun `round-trips a list of paths preserving order`() {
        val paths = listOf("/data/a.jpg", "/data/b.jpg", "/data/c.jpg")
        assertEquals(paths, StringListJson.decode(StringListJson.encode(paths)))
    }

    @Test fun `single element round-trips`() {
        val paths = listOf("/files/content_images/x.jpg")
        assertEquals(paths, StringListJson.decode(StringListJson.encode(paths)))
    }

    @Test fun `malformed json decodes to empty rather than throwing`() {
        assertEquals(emptyList<String>(), StringListJson.decode("not json"))
        assertEquals(emptyList<String>(), StringListJson.decode("{\"k\":1}"))
    }
}
