/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ai.GeminiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiServiceRagTest {

  companion object {
    private const val TAG = "GeminiServiceRagTest"
  }

  @Test
  fun testPdfRagRetrieval() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val service = GeminiService(context)

    // Test utterance querying checking accounts info from the PDF
    val utterance = "how much is the fee for Everyday Checking or Clear Access Banking?"
    Log.i(TAG, "Sending utterance: '$utterance'")

    val suggestion = service.getConversationSuggestion(utterance, null)
    Log.i(TAG, "RAG suggestion response: '$suggestion'")

    assertNotNull("Suggestion response should not be null", suggestion)
    assertTrue("Suggestion should not be empty", suggestion.trim().isNotEmpty())
    
    // Check if the response matches key information from our PDF
    val suggestionLower = suggestion.lowercase()
    val hasEverydayOrClearAccess = suggestionLower.contains("everyday") || 
                                   suggestionLower.contains("checking") || 
                                   suggestionLower.contains("clear access") || 
                                   suggestionLower.contains("fee")
    assertTrue("Suggestion should mention checking or fee details: $suggestion", hasEverydayOrClearAccess)
  }
}
