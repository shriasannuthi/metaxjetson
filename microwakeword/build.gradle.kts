/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess.microwakeword"
  compileSdk = 35
  ndkVersion = "27.2.12479018"

  defaultConfig {
    minSdk = 31
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")

    externalNativeBuild {
      cmake {
        arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("com.google.code.gson:gson:2.11.0")
}
