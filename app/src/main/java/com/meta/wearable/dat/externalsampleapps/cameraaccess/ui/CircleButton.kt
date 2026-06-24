/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

@Composable
fun CircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
  Button(
      modifier = modifier.aspectRatio(1f),
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = AppColor.WarmPanel,
              contentColor = AppColor.WfDeepRed,
          ),
      shape = CircleShape,
      contentPadding = PaddingValues(0.dp),
      content = content,
  )
}

@Composable
fun CaptureButton(onClick: () -> Unit) {
  CircleButton(onClick = onClick) {
    Icon(
        imageVector = Icons.Filled.PhotoCamera,
        contentDescription = stringResource(R.string.capture_photo),
        tint = AppColor.WfDeepRed,
    )
  }
}

@Composable
fun ScanDocumentButton(onClick: () -> Unit) {
  CircleButton(onClick = onClick) {
    Icon(
        imageVector = Icons.Filled.DocumentScanner,
        contentDescription = "Scan document",
        tint = AppColor.WfDeepRed,
    )
  }
}

@Composable
fun VoiceTestButton(onClick: () -> Unit) {
  CircleButton(onClick = onClick) {
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = "Test voice input",
        tint = AppColor.WfDeepRed,
    )
  }
}
