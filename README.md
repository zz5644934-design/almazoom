# AlmaZoom · 1000× Zoom for Samsung Camera

AlmaZoom extends the zoom slider in the stock Samsung Camera app up to **1000× digital zoom**. It works as a Vector module in the rear camera's Photo mode with JPEG.

**[Download AlmaLens 0.15.1 for One UI 9 (Camera 17.0.00.43)](https://github.com/zz5644934-design/almazoom/releases/download/v0.15.1/AlmaLens-0.15.1.apk)** · [Русская версия](README.ru.md)

**[Download One UI 8.0 beta 5 (Camera 16.0.00.66)](https://github.com/zz5644934-design/almazoom/releases/download/v1.1.3-beta5/AlmaZoom-v1.1.3-beta5.apk)**

**[Download Xiaomi Camera beta](https://github.com/zz5644934-design/almazoom/releases/download/v1.1.3-xiaomi-beta1/AlmaZoom-v1.1.3-xiaomi-beta1.apk)**

Requires a rooted Android device with [Vector](https://github.com/JingMatrix/Vector) already set up.

## Compatibility

| Device | Samsung Camera | Status |
| --- | --- | --- |
| **Galaxy S25 Ultra (SM-S938U1)** | **17.0.00.43** | Camera hooks checked against this build. A previous version showed extended zoom on-device. The original 1.1.2 build targets this APK. JPEG saving still needs an on-device check. |
| Samsung Galaxy running One UI 8.0 | 16.0.00.66 | The beta 5 preview zoom fix was confirmed on-device by a tester. Saved JPEG output still needs separate verification. |
| Other Galaxy models or Camera versions | Varies | Untested. Check your exact Camera version before reporting compatibility. |

## Xiaomi Camera beta

Targets Xiaomi Camera **6.3.007270.1 (630072701)** on Android 13 or later. Phone model and runtime compatibility are unconfirmed. Enable the **Xiaomi Camera** (`com.android.camera`) scope in Vector, restart the camera, select rear **Photo** with regular JPEG and Live Photo off, then zoom to the app's maximum. Tap **AlmaZoom** to open the extended slider. The crop applies to the processed JPEG before storage. Standard-resolution single shots are the intended test case. Logs: `PREVIEW_ACTIVE`, `SHOT_QUEUED`, `JPEG_CROPPED`.

## Install

1. Install the [AlmaZoom APK](https://github.com/zz5644934-design/almazoom/releases/download/v1.1.3-beta5/AlmaZoom-v1.1.3-beta5.apk).
2. Enable **AlmaZoom** in Vector and set its scope to **Samsung Camera** (`com.sec.android.app.camera`).
3. Fully close Samsung Camera and reopen it. In **Photo**, use the rear camera and JPEG, such as the standard 12 MP setting with HEIF off.
4. Open the zoom slider and scroll past the stock limit. The magnifier in Camera's quick controls toggles extended zoom.

## What happens above the stock limit

Samsung Camera shoots and processes the original at its own maximum. AlmaZoom crops the processed JPEG around the same center as the enlarged preview before Gallery registration. At 400× over a 100× base, the crop uses the central quarter of the image in each dimension. The output JPEG's longest edge is capped at 2048 pixels.

AlmaZoom sizes the added slider range to match the camera's stock limit. Slider math was tested at 10×, 20×, 30×, 50× and 100×. Camera support also depends on the preview and JPEG-saving hooks in the installed Camera build.

## Build from source

Use JDK 17 and Android SDK Platform 35. On Windows, run the same task with `gradlew.bat`.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Installing over a previous build requires the same signing certificate.

## Report a device test

Photograph a fixed subject at the stock maximum, then at twice that value on the AlmaZoom slider. Compare the saved JPEGs in Gallery. Include the model, Android version, Samsung Camera version and matching `AlmaZoom` lines from the Vector log.

- `CAMERA_PROFILE_UNAVAILABLE` reports an incompatible camera profile.
- `CROPPED_BEFORE_PUBLICATION` confirms the module cropped the JPEG.
- `SAMSUNG_SCAN_RETURNED uriPresent=true` confirms Samsung's scanner returned an image URI.

## AlmaLens (newer, One UI 9)

[`almalens`](almalens) combines the AlmaLens effects with the AlmaZoom zoom logic in one module for Samsung Camera on One UI 9. Zoom to 1000x, 60 fps preview, the draft that the gallery shows is cropped like the final photo, and the gallery fades in a spoiler while a photo is still being processed. See [`almalens/CHANGES.md`](almalens/CHANGES.md).
