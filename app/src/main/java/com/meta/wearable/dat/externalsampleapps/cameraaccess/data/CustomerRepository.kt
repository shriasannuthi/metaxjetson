/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.data

import android.content.Context
import com.google.gson.Gson
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomerRepository(
  private val context: Context,
  private val gson: Gson = Gson(),
) {
  suspend fun loadCustomers(): List<Customer> =
    withContext(Dispatchers.IO) {
      context.assets.open(CUSTOMERS_ASSET).use { inputStream ->
        InputStreamReader(inputStream).use { reader ->
          gson.fromJson(reader, CustomerDatabase::class.java)?.customers.orEmpty()
        }
      }
    }

  suspend fun findCustomerById(customerId: String): Customer? =
    loadCustomers().firstOrNull { it.id.equals(customerId, ignoreCase = true) }

  fun faceAssetPath(customer: Customer): String = "$FACES_ASSET_PATH/${customer.faceImage}"

  private companion object {
    const val CUSTOMERS_ASSET = "customers.json"
    const val FACES_ASSET_PATH = "faces"
  }
}
