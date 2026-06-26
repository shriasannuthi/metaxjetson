/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

@Composable
fun WfBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    showProductName: Boolean = true,
) {
  Column(
      modifier = modifier,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
        modifier = Modifier.width(size).height(size),
        shape = RoundedCornerShape(10.dp),
        color = AppColor.WfRed,
        shadowElevation = 2.dp,
    ) {
      Box {
        Image(
            painter = painterResource(id = R.drawable.wf_symbol),
            contentDescription = "WF Meta",
            modifier = Modifier.align(Alignment.Center).fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (showProductName) {
      Text(
          text = "WF Meta",
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 10.dp),
      )
    }
  }
}
