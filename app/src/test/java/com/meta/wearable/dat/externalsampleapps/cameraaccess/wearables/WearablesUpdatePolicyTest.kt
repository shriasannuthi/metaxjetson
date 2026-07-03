package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearablesUpdatePolicyTest {
  @Test
  fun onlyDedicatedDatErrorRequiresGlassesAppUpdate() {
    assertTrue(
        WearablesUpdatePolicy.requiresDatAppUpdate(
            DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED
        )
    )
    assertFalse(
        WearablesUpdatePolicy.requiresDatAppUpdate(DeviceSessionError.SESSION_ENDED_BY_DEVICE)
    )
    assertFalse(WearablesUpdatePolicy.requiresDatAppUpdate(DeviceSessionError.BATTERY_CRITICAL))
  }

  @Test
  fun successfulSessionStartClearsDatUpdateState() {
    assertTrue(WearablesUpdatePolicy.clearsDatAppUpdate(DeviceSessionState.STARTED))
    assertFalse(WearablesUpdatePolicy.clearsDatAppUpdate(DeviceSessionState.STARTING))
    assertFalse(WearablesUpdatePolicy.clearsDatAppUpdate(DeviceSessionState.STOPPED))
  }

  @Test
  fun datUpdateAllowsRetryButFirmwareAndMissingDeviceStillBlock() {
    assertTrue(
        WearablesUiState(hasActiveDevice = true, isDatAppUpdateRequired = true).canStartStreaming
    )
    assertFalse(
        WearablesUiState(hasActiveDevice = true, isFirmwareUpdateRequired = true).canStartStreaming
    )
    assertFalse(
        WearablesUiState(hasActiveDevice = true, isFirmwareUpdateRequired = true, isDatAppUpdateRequired = true)
            .canStartStreaming
    )
    assertFalse(WearablesUiState(isDatAppUpdateRequired = true).canStartStreaming)
  }
}
