package app.voicenote.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiWizardTurnClientTest {
    private val request = WizardTurnRequest(
        draft = WizardDraft(
            id = "draft-1",
            transcript = listOf(
                TranscriptTurn(
                    id = "turn-1",
                    speaker = TranscriptSpeaker.USER,
                    text = "Need a concise summary",
                    createdAtEpochMillis = 100L,
                ),
            ),
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        ),
        session = SessionState(
            draftId = "draft-1",
            phase = SessionPhase.RUNNING_WIZARD_TURN,
            updatedAtEpochMillis = 100L,
        ),
        userTurn = TranscriptTurn(
            id = "turn-1",
            speaker = TranscriptSpeaker.USER,
            text = "Need a concise summary",
            createdAtEpochMillis = 100L,
        ),
    )

    @Test
    fun `valid structured response parsing returns a wizard turn response`() {
        val client = OpenAiWizardTurnClient(
            config = OpenAiWizardClientConfig(apiKey = "test-key", model = "test-model"),
            transport = StubOpenAiResponsesTransport(
                """
                {
                  "output_text": "{\"wizardMessage\":\"Here is a local summary.\",\"nextDraftStatus\":\"IN_PROGRESS\",\"nextSessionPhase\":\"AWAITING_USER_TURN\"}"
                }
                """.trimIndent(),
            ),
            assets = WizardTurnContractAssets.loadDefault(),
        )

        val response = client.runTurn(request)

        assertEquals(
            WizardTurnResponse(
                wizardMessage = "Here is a local summary.",
                nextDraftStatus = DraftStatus.IN_PROGRESS,
                nextSessionPhase = SessionPhase.AWAITING_USER_TURN,
            ),
            response,
        )
    }

    @Test
    fun `malformed response rejection throws a client exception`() {
        val client = OpenAiWizardTurnClient(
            config = OpenAiWizardClientConfig(apiKey = "test-key", model = "test-model"),
            transport = StubOpenAiResponsesTransport(
                """
                {
                  "output_text": "not-json"
                }
                """.trimIndent(),
            ),
            assets = WizardTurnContractAssets.loadDefault(),
        )

        assertFailsWith<WizardTurnClientException> {
            client.runTurn(request)
        }
    }

    @Test
    fun `missing required fields handling rejects incomplete structured output`() {
        val client = OpenAiWizardTurnClient(
            config = OpenAiWizardClientConfig(apiKey = "test-key", model = "test-model"),
            transport = StubOpenAiResponsesTransport(
                """
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "{\"wizardMessage\":\"Still drafting.\",\"nextDraftStatus\":\"IN_PROGRESS\"}"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
            assets = WizardTurnContractAssets.loadDefault(),
        )

        val exception = assertFailsWith<WizardTurnClientException> {
            client.runTurn(request)
        }

        assertEquals(
            "Structured output is missing required fields: nextSessionPhase",
            exception.message,
        )
    }

    @Test
    fun `environment config falls back to the current default model`() {
        val config = OpenAiWizardClientConfig.fromEnvironment(
            env = mapOf(
                "OPENAI_API_KEY" to "test-key",
            ),
        )

        assertEquals("gpt-5-nano", config.model)
    }

    private class StubOpenAiResponsesTransport(
        private val responseBody: String,
    ) : OpenAiResponsesTransport {
        override fun createResponse(requestBody: String, config: OpenAiWizardClientConfig): String = responseBody
    }
}
