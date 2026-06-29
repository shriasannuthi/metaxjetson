/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Configuration for a microWakeWord model.
 *
 * Matches the JSON configuration format used by ESPHome microWakeWord models.
 */
data class MicroWakeWordModelConfig(
    @SerializedName("wake_word") val wakeWord: String,
    @SerializedName("author") val author: String = "",
    @SerializedName("website") val website: String = "",
    @SerializedName("model") val model: String,
    @SerializedName("trained_languages") val trainedLanguages: List<String> = emptyList(),
    @SerializedName("version") val version: Int = 1,
    @SerializedName("micro") val micro: MicroConfig,
) {
  val modelAssetPath: String
    get() = "$WAKEWORD_ASSET_DIR/$model"

  data class MicroConfig(
      @SerializedName("probability_cutoff") val probabilityCutoff: Float,
      @SerializedName("feature_step_size") val featureStepSize: Int,
      @SerializedName("sliding_window_size") val slidingWindowSize: Int,
      @SerializedName("tensor_arena_size") val tensorArenaSize: Int? = null,
      @SerializedName("minimum_esphome_version") val minimumEsphomeVersion: String? = null,
  )

  companion object {
    private const val TAG = "MicroWakeWordModelConfig"
    private const val WAKEWORD_ASSET_DIR = "wakeword"
    private val gson = Gson()

    fun loadFromAsset(context: Context, assetPath: String): MicroWakeWordModelConfig {
      val jsonContent =
          context.assets.open(assetPath).bufferedReader().use { it.readText() }
      return gson.fromJson(jsonContent, MicroWakeWordModelConfig::class.java)
    }

    fun loadAvailableModels(context: Context): List<MicroWakeWordModelConfig> {
      val models = mutableListOf<MicroWakeWordModelConfig>()
      val assetFiles = context.assets.list(WAKEWORD_ASSET_DIR) ?: emptyArray()

      for (fileName in assetFiles) {
        if (!fileName.endsWith(".json")) continue
        runCatching {
              val config = loadFromAsset(context, "$WAKEWORD_ASSET_DIR/$fileName")
              models.add(config)
              Log.d(TAG, "Loaded wake word model: ${config.wakeWord}")
            }
            .onFailure { error ->
              Log.w(TAG, "Failed to load wake word config: $fileName", error)
            }
      }

      return models.sortedBy { it.wakeWord }
    }
  }
}
