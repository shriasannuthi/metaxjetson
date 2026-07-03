package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError

internal object WearablesUpdatePolicy {
  fun requiresDatAppUpdate(error: DeviceSessionError): Boolean =
      error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED

  fun clearsDatAppUpdate(state: DeviceSessionState): Boolean =
      state == DeviceSessionState.STARTED
}
