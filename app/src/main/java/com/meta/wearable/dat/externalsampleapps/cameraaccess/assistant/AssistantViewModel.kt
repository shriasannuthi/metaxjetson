package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AssistantMode { PHASE_5A, PHASE_5B }

data class AssistantUiState(
    val customers: List<Customer> = emptyList(),
    val customer: Customer? = null,
    val mode: AssistantMode = AssistantMode.PHASE_5A,
    val conversation: List<ConversationTurn> = emptyList(),
    val streamedAnswer: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = CustomerRepository(application)
  private val sessions = SessionManager(application)
  private val gemini = GeminiService(BuildConfig.GEMINI_API_KEY)
  private val _uiState = MutableStateFlow(AssistantUiState())
  val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
  private var answerJob: Job? = null

  init {
    viewModelScope.launch {
      runCatching { repository.loadCustomers() }
          .onSuccess { customers ->
            val customer = customers.firstOrNull()
            customer?.let { sessions.start(it.id) }
            _uiState.update {
              it.copy(
                  customers = customers,
                  customer = customer,
                  conversation = sessions.fullConversation(),
                  error = if (customers.isEmpty()) "No customers found" else null,
              )
            }
          }
          .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
    }
  }

  fun setMode(mode: AssistantMode) { _uiState.update { it.copy(mode = mode) } }

  fun selectCustomer(customer: Customer) {
    if (_uiState.value.customer?.id == customer.id) return
    answerJob?.cancel()
    sessions.end()
    sessions.start(customer.id)
    _uiState.update {
      it.copy(
          customer = customer,
          conversation = sessions.fullConversation(),
          streamedAnswer = "",
          isLoading = false,
          error = null,
      )
    }
  }

  fun ask(question: String) {
    val customer = _uiState.value.customer ?: return
    val cleanQuestion = question.trim()
    if (cleanQuestion.isEmpty() || _uiState.value.isLoading) return
    sessions.start(customer.id)
    val context = if (_uiState.value.mode == AssistantMode.PHASE_5B) {
      sessions.fullConversation()
    } else {
      emptyList()
    }
    sessions.append(ConversationRole.CUSTOMER, cleanQuestion)
    _uiState.update {
      it.copy(
          conversation = sessions.fullConversation(),
          streamedAnswer = "",
          isLoading = true,
          error = null,
      )
    }

    val answer = StringBuilder()
    answerJob = viewModelScope.launch {
      gemini.streamAnswer(customer, cleanQuestion, context)
          .catch { error ->
            _uiState.update { it.copy(error = error.message ?: "Unable to get a response") }
          }
          .onCompletion { cause ->
            // A cancelled request can belong to a customer that has just been switched out.
            if (cause == null && answer.isNotBlank()) {
              sessions.append(ConversationRole.ASSISTANT, answer.toString())
            }
            _uiState.update {
              it.copy(conversation = sessions.fullConversation(), isLoading = false)
            }
          }
          .collect { chunk ->
            answer.append(chunk)
            _uiState.update { it.copy(streamedAnswer = answer.toString()) }
          }
    }
  }

  fun endSession() {
    answerJob?.cancel()
    sessions.end()
    _uiState.update { it.copy(conversation = emptyList(), streamedAnswer = "", isLoading = false) }
  }

  override fun onCleared() {
    answerJob?.cancel()
    super.onCleared()
  }

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST") return AssistantViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
