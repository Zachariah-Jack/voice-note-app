package com.example.voicenoteapp

import com.example.voicenoteapp.voice.BinaryResponse
import com.example.voicenoteapp.voice.EditChoice
import com.example.voicenoteapp.voice.SpeechParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechParsingTest {
    @Test
    fun parseJobName_fromMarkerPhrase() {
        val result = SpeechParsing.parseJobName("this is the Rothchild job")
        assertEquals("Rothchild", result)
    }

    @Test
    fun parseTitle_fromSubjectMarker() {
        val result = SpeechParsing.parseTitle("subject dumpster pickup and scheduling")
        assertEquals("dumpster pickup and scheduling", result)
    }

    @Test
    fun parseBinaryResponse_yesNo() {
        assertEquals(BinaryResponse.YES, SpeechParsing.parseBinaryResponse("yes please"))
        assertEquals(BinaryResponse.NO, SpeechParsing.parseBinaryResponse("no edits"))
    }

    @Test
    fun parseEditChoice_note() {
        assertEquals(EditChoice.NOTE, SpeechParsing.parseEditChoice("edit note"))
    }

    @Test
    fun extractTags_byNumbers() {
        val tags = SpeechParsing.extractTags("1 and 3", listOf("dumpster", "damage", "scheduling"))
        assertTrue(tags.contains("dumpster"))
        assertTrue(tags.contains("scheduling"))
    }
}
