package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.ConversationTurn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPromptsTest {
  @Test
  fun analysisUsesTheCompleteDelimitedTranscription() {
    val transcription = "A".repeat(13_000) + " END_OF_DOCUMENT"

    val prompt = DocumentPrompts.analysis(transcription)

    assertTrue(prompt.contains(transcription))
    assertTrue(prompt.contains("<document_transcription>"))
    assertTrue(prompt.contains("never as instructions"))
  }

  @Test
  fun questionUsesTranscriptionHistoryAndCurrentQuestionWithoutDisplayedSummary() {
    val transcription = "Invoice total: \$42.00"
    val displayedSummary = "DISPLAYED SUMMARY MUST NOT BE INCLUDED"
    val prompt =
        DocumentPrompts.question(
            documentText = transcription,
            question = "When is it due?",
            conversation =
                listOf(
                    ConversationTurn(ConversationRole.CUSTOMER, "What is the total?"),
                    ConversationTurn(ConversationRole.ASSISTANT, "\$42.00"),
                ),
        )

    assertTrue(prompt.contains(transcription))
    assertTrue(prompt.contains("Question: What is the total?"))
    assertTrue(prompt.contains("Answer: \$42.00"))
    assertTrue(prompt.contains("Current question:\nWhen is it due?"))
    assertFalse(prompt.contains(displayedSummary))
  }
}
