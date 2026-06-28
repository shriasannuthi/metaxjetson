# Local Meta Glasses Assistant

An Android application that connects to Meta AI glasses and processes customer questions and
document scans on a local Windows laptop. Runtime AI processing does not require internet access.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Share captured photos
- Match enrolled faces locally with ML Kit and TensorFlow Lite
- Answer customer and document questions with local Gemma 3 through Ollama
- Convert document photos to structured Markdown with local PP-StructureV3 and PP-OCRv5
- Open firmware and DAT glasses app update flows when required

## Local AI Setup

Follow the complete beginner-oriented guide in
[docs/WINDOWS_LOCAL_SETUP.md](docs/WINDOWS_LOCAL_SETUP.md). It covers Ollama, Gemma, Python,
PaddleOCR, USB-only hosting, Android configuration, and the final no-network test.

The runtime architecture is:

```text
Android app -> phone 127.0.0.1:8000
            -> USB cable / adb reverse
            -> laptop 127.0.0.1:8000
               |-> Ollama/Gemma on RTX 4060
               `-> PP-StructureV3/PP-OCRv5 on CPU
```

The phone must remain connected with a data-capable USB cable while local AI features are in use.
Wi-Fi, Ethernet, cellular data, mobile hotspots, and USB tethering are not used. Bluetooth remains
enabled on the phone only because the glasses require it.

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Add your personal access token (classic) to the `local.properties` file (see [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup))
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Capture photos
   - View and save captured photos
   - Disconnect from the device
1. If a firmware update is required, tap "Update firmware" from the connection screen.
1. If session start reports that the app on the glasses is outdated, tap "Update app on glasses" from the connection screen.

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
