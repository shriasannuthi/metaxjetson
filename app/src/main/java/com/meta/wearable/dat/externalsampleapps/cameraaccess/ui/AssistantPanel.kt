package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant.AssistantUiState

@Composable
fun AssistantPanel(
    state: AssistantUiState,
    onDismiss: () -> Unit,
    onModeChanged: (AssistantMode) -> Unit,
    onCustomerChanged: (Int) -> Unit,
    onAsk: (String) -> Unit,
    onEndSession: () -> Unit,
) {
  var question by remember { mutableStateOf("") }
  val customer = state.customer
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("RM Assistant") },
      text = {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(customer?.name ?: "Loading customer…", style = MaterialTheme.typography.titleMedium)
              Text(customer?.profile.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onCustomerChanged(1) }, enabled = state.customers.size > 1) {
              Text("Next")
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.mode == AssistantMode.PHASE_5A) {
              Button(onClick = {}) { Text("5a · Fast") }
              OutlinedButton(onClick = { onModeChanged(AssistantMode.PHASE_5B) }) { Text("5b · Context") }
            } else {
              OutlinedButton(onClick = { onModeChanged(AssistantMode.PHASE_5A) }) { Text("5a · Fast") }
              Button(onClick = {}) { Text("5b · Context") }
            }
          }
          Text(
              if (state.mode == AssistantMode.PHASE_5A)
                "Uses this question and the customer's stored banking data."
              else
                "Uses this question, stored banking data, and all ${state.conversation.size} turns in this session.",
              style = MaterialTheme.typography.labelSmall,
          )
          OutlinedTextField(
              value = question,
              onValueChange = { question = it },
              label = { Text("Customer's question") },
              enabled = !state.isLoading,
              minLines = 2,
              modifier = Modifier.fillMaxWidth(),
          )
          if (state.isLoading && state.streamedAnswer.isBlank()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
          }
          if (state.streamedAnswer.isNotBlank()) {
            Text("Suggested response", style = MaterialTheme.typography.labelMedium)
            Text(state.streamedAnswer, style = MaterialTheme.typography.bodyLarge)
          }
          state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        Button(
            enabled = question.isNotBlank() && customer != null && !state.isLoading,
            onClick = {
              onAsk(question)
              question = ""
            },
        ) { Text("Ask") }
      },
      dismissButton = {
        Row {
          TextButton(onClick = onEndSession) { Text("End session") }
          TextButton(onClick = onDismiss) { Text("Close") }
        }
      },
      modifier = Modifier.padding(vertical = 8.dp),
  )
}

