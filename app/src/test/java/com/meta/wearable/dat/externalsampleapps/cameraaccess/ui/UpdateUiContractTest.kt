package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiContractTest {
  @Test
  fun datUpdateUsesRetryAction() {
    assertEquals(R.string.retry_stream_button_title, streamActionLabel(true))
    assertEquals(R.string.stream_button_title, streamActionLabel(false))
  }

  @Test
  fun datUpdateCopyExplainsReturnAndRetry() {
    val candidates =
        listOf(
            File("app/src/main/res/values/strings.xml"),
            File("src/main/res/values/strings.xml"),
        )
    val strings = candidates.first { it.isFile }.readText()

    assertTrue(strings.contains("Complete the glasses app update in Meta AI"))
    assertTrue(strings.contains("return here, then retry streaming"))
    assertTrue(strings.contains("Update app on glasses"))
    assertTrue(strings.contains("Retry streaming"))
    assertTrue(strings.contains("Your glasses firmware needs an update"))
    assertTrue(strings.contains("Complete the firmware and glasses app updates in Meta AI"))
  }
}
