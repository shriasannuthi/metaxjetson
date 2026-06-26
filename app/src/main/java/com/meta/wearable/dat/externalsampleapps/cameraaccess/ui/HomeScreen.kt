/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// HomeScreen - DAT Registration Entry Point
//
// This screen handles DAT device registration.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun HomeScreen(
    viewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  val context = LocalContext.current

  Column(
      modifier =
          modifier
              .fillMaxSize()
              .background(AppColor.WarmSurface)
              .verticalScroll(scrollState)
              .padding(horizontal = 24.dp, vertical = 28.dp)
              .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(28.dp),
  ) {
    Spacer(modifier = Modifier.weight(1f))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      WfBrandMark(size = 78.dp, showProductName = false)
      Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = AppColor.Ink,
          textAlign = TextAlign.Center,
      )
      Text(
          text = "Connect your glasses once, then move into live customer assist and document intelligence.",
          style = MaterialTheme.typography.bodyLarge,
          color = AppColor.Slate,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 8.dp),
      )
      Column(
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        TipItem(
            iconResId = R.drawable.smart_glasses_icon,
            title = stringResource(R.string.home_tip_video_title),
            text = stringResource(R.string.home_tip_video),
        )
        TipItem(
            iconResId = R.drawable.sound_icon,
            title = stringResource(R.string.home_tip_audio_title),
            text = stringResource(R.string.home_tip_audio),
        )
        TipItem(
            iconResId = R.drawable.walking_icon,
            title = stringResource(R.string.home_tip_hands_title),
            text = stringResource(R.string.home_tip_hands),
        )
      }
    }
    Spacer(modifier = Modifier.weight(1f))

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // App Registration Button
      Text(
          text = stringResource(R.string.home_redirect_message),
          color = AppColor.Slate,
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 12.dp),
      )
      SwitchButton(
          label = stringResource(R.string.register_button_title),
          enabled = uiState.canStartRegistration,
          onClick = {
            activity?.let { viewModel.startRegistration(it) }
                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
          },
      )
    }
  }
}

@Composable
private fun TipItem(
    iconResId: Int,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = AppColor.Mist,
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
      Icon(
          painter = painterResource(id = iconResId),
          contentDescription = "Tip icon",
          tint = AppColor.DeepBlue,
          modifier = Modifier.padding(top = 2.dp).width(22.dp),
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColor.Ink,
        )
        Text(text = text, color = AppColor.Slate, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}
