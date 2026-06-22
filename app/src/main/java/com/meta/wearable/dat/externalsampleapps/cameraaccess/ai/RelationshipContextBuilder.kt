package com.meta.wearable.dat.externalsampleapps.cameraaccess.ai

import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import java.util.ArrayDeque

class RelationshipContextBuilder(
    private val maxTurns: Int = 4
) {
    private val history = ArrayDeque<String>()

    fun remember(question: String, answer: String) {
        if (history.size >= maxTurns) history.removeFirst()
        history.addLast("Customer: $question\nRM: $answer")
    }

    fun build(
        question: String,
        customer: Customer?,
        includeHistory: Boolean = true
    ): String {

        val customerContext =
            customer?.let {
                """
                CUSTOMER PROFILE
                Name: ${it.name}
                Profile: ${it.profile}
                Last Visit: ${it.lastVisit}

                Accounts:
                ${
                    it.accounts.take(2).joinToString("\n") {
                        "- ${it.type}: ${it.balance}"
                    }
                }

                Recent History:
                ${
                    it.history.takeLast(2).joinToString("\n") {
                        "- ${it.notes}"
                    }
                }
                """.trimIndent()
            } ?: "No identified customer."

        val conversation =
            if (includeHistory && history.isNotEmpty()) {
                """
                RECENT CONVERSATION
                ${history.joinToString("\n")}
                """
            } else ""

        return """
        You are a banking relationship manager assistant.

        Rules:
        - Answer ONLY current question.
        - Use customer profile only when relevant.
        - Ignore old conversation unless needed.
        - Keep response under 4 sentences.

        $customerContext

        $conversation

        CURRENT QUESTION:
        $question
        """.trimIndent()
    }
}