# 0.16.0

Zoom only. The module is now just the zoom; the visual extras of 0.14-0.15 are switched off.

- Kept: zoom up to 1000x, the magnifier in the camera quick settings, zoom restore, JPEG and draft crop, the gallery
  spoiler while a photo is processed (`GalleryHooks`, `ProcessingMarker`), the compact zoom labels on the buttons.
- Removed: the liquid glass and the glint on the zoom scale (`updateGlass` returns at once, the scroll glint is no
  longer started), the colored focus ring (`installFocusHooks` is not called), the 60 fps preview
  (`PreviewFrameRate` is not installed) and its checkbox in the settings dialog.
- Renamed AlmaLens to AlmaZoom in the app name, descriptions, theme, dialog title and log lines. The settings key
  `almalens_zoom1000` and the marker folder `DCIM/.almalens` keep their names so that nothing stored earlier is lost.
- versionCode 29, versionName 0.16.0.

# 0.15.1

- The gallery spoiler no longer relies on Samsung's `isPostProcessing` flag: that flag lives inside the JPEG
  (SEF marker) and is lost when the photo is cropped, so the gallery saw a finished photo from the first second.
  The camera now creates an empty hidden marker `DCIM/.almalens/<photo name>` when the shot starts and removes it when
  the final photo is published; the gallery blurs and veils the page of that photo while the marker exists.
- The camera logs the views that look like the round gallery thumbnail when it cannot find it.

# 0.15.0

- New scope: Samsung Gallery (`com.sec.android.gallery3d`). While the viewer shows a photo that Samsung still marks as
  post-processed (`MediaItem.isPostProcessing`, the check Samsung's own `PostProcessingDefender` uses), the page is
  blurred and covered with the spoiler shader. It is removed when Samsung reports the photo as finished
  (or after 25 s). Enable the module for the gallery in the framework manager for it to work.

# 0.14.5

- Zoom labels (for example "538x") are no longer squeezed sideways to fit a fixed-width button. The font is made
  smaller evenly (down to 55%), so the digits keep their proportions instead of looking narrow and tall.
- Small buttons show a compact number from 100x up (538 instead of 538,3); the main readout keeps the decimals.

# 0.14.4

- The draft that Samsung shows in the gallery while the photo is processed (`<name>_temp.jpg`, written by
  `PostSavingModuleDraftWrite`) is cropped with the same factor as soon as it is written. Before, only the processed
  photo was cropped, so the gallery first showed the 100x frame and then jumped to the real zoom.
- The processing spoiler is an AGSL shader now (twinkling particles that drift, with a soft highlight sweeping over
  them, thin rim); the plain-dots version stays as a fallback.
- JPEG quality of cropped photos 94 -> 97.
- Log lines for the spoiler and the draft (`DRAFT_WRITTEN`, `DRAFT_CROPPED`).

# 0.14.3

- The extended digital zoom (for example 500x) is remembered when the camera goes to the background, so after looking
  at the photo and coming back the zoom is where it was instead of falling back to Samsung's 100x.
  It is restored if you return within 10 minutes and the zoom is still at Samsung's maximum.
- While a zoomed photo is processed, the round gallery thumbnail of the camera is blurred and covered with drifting,
  twinkling dots, like a spoiler in a messenger. It goes away when the final photo is published (25 s at most).
- Extra log lines for the saving flow (`OUTPUT_PATH`, `SCAN_FILE`, `INSERT_TO_DB`) to find out why the gallery first
  shows the 100x image and then jumps to the cropped one.

# 0.14.2

- 60 fps preview stays on by default (the switch in the settings dialog still works), but it now asks for the
  widest range (15..60) instead of a fixed 60..60. Auto exposure can again lengthen the exposure in dim light.
  The fixed range capped every frame and every photo at 1/60 s, which made them dark and noisy.
- The setting uses a new key, `high_fps_preview`; the old `experimental_fast_preview` value is dropped.
- The available FPS options of the camera are written to the log once per session.

# 0.14.1

Fixes found in a code review of 0.14.

- A single exception no longer disables the digital zoom wheel until the camera is reopened. The wheel now pauses for
  2 s and retries; it is turned off for good only after 5 errors within a minute.
- Settings read through reflection no longer throw NullPointerException when a value is missing
  (`readSetting`, camera facing, max zoom level, resolution). A missing value now means "feature inactive".
- The focus ring and the high-FPS check now use the same field names as the rest of the module on both camera
  profiles (they were hardcoded to the obfuscated names).
- Cropped JPEGs keep the real detail of the cropped area: the long edge is at least 2048 px, at most the source size
  and 4096 px (it used to be capped at 2048 px for every crop). Decoding is limited to 16 MP to bound memory use.
- EXIF of cropped JPEGs: more tags are copied (lens, exposure bias, metering, color space, ...),
  `FocalLengthIn35mmFilm` is scaled by the crop factor and `DigitalZoomRatio` is written.
- Removed the unused `JpegCrop.cropBytes`. Hidden `.almazoom-*.jpg` temp files older than 10 minutes are deleted.
- The crop factor locked at shutter time also survives `createCaptureInfo` running on another thread (1.5 s window).
- The module polls every 400 ms instead of 220 ms while the camera is not in the photo mode.
- UI texts follow the system language (Russian or English).
- Log prefixes, theme and thread names use AlmaZoom instead of AlmaZoom.

Not changed: `FocusRing` still redraws every frame while the ring is visible (needed for the rotation animation).
