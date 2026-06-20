package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerSession
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the banking conversation independently of the DAT device session. */
class SessionManager(context: Context, private val gson: Gson = Gson()) {
  private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
  private val _session = MutableStateFlow<CustomerSession?>(null)
  val session: StateFlow<CustomerSession?> = _session.asStateFlow()

  @Synchronized
  fun start(customerId: String): CustomerSession {
    _session.value?.takeIf { it.customerId == customerId && it.endedAtMs == null }?.let { return it }
    val saved = load(customerId).lastOrNull { it.endedAtMs == null }
    return (saved ?: CustomerSession(UUID.randomUUID().toString(), customerId, System.currentTimeMillis()))
        .also { _session.value = it }
  }

  @Synchronized
  fun append(role: ConversationRole, text: String) {
    if (text.isBlank()) return
    val updated = requireNotNull(_session.value) { "Start a customer session first" }
        .copy(turns = _session.value!!.turns + ConversationTurn(role, text.trim()))
    _session.value = updated
    persist(updated)
  }

  /** Phase 5b deliberately returns every turn in this session, in chronological order. */
  fun fullConversation(): List<ConversationTurn> = _session.value?.turns.orEmpty()

  @Synchronized
  fun end(): CustomerSession? {
    val ended = _session.value?.copy(endedAtMs = System.currentTimeMillis()) ?: return null
    persist(ended)
    _session.value = null
    return ended
  }

  fun sessionsFor(customerId: String): List<CustomerSession> = load(customerId)

  private fun load(customerId: String): List<CustomerSession> {
    val json = preferences.getString(key(customerId), null) ?: return emptyList()
    return runCatching {
      val type = object : TypeToken<List<CustomerSession>>() {}.type
      gson.fromJson<List<CustomerSession>>(json, type).orEmpty()
    }.getOrDefault(emptyList())
  }

  private fun persist(session: CustomerSession) {
    val sessions = load(session.customerId).toMutableList()
    val existing = sessions.indexOfFirst { it.id == session.id }
    if (existing >= 0) sessions[existing] = session else sessions += session
    preferences.edit().putString(key(session.customerId), gson.toJson(sessions)).apply()
  }

  private fun key(customerId: String) = "sessions_$customerId"

  companion object { private const val PREFERENCES = "bank_rm_conversations" }
}

