package com.example.voicenoteapp

import org.junit.Assert.assertEquals
import org.junit.Test

class DeriveTitleTest {
    @Test
    fun deriveFromBodyWhenTitleBlank() {
        val body = "This is a long body text that should be trimmed into title"
        val result = deriveTitleForTest("", body)
        assertEquals("This is a long body text that should be tr", result)
    }
}

private fun deriveTitleForTest(inputTitle: String, body: String): String {
    if (inputTitle.isNotBlank()) return inputTitle.trim()
    val fallback = body.trim().replace("\n", " ")
    return if (fallback.isBlank()) "Untitled note" else fallback.take(40)
}
