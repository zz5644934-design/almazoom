# Reddit posts (English)

Check each subreddit's self-promotion rules before posting. Attach before/after photos (stock max vs AlmaZoom).

## Target: r/GalaxyS25Ultra, r/S25Ultra, r/samsung (flair: Mod / Discussion if available)

**Title:** Made a Vector module that extends Samsung Camera zoom to 1000x (S25 Ultra, One UI 9), samples inside

**Body:**
I built AlmaZoom (AlmaLens), a free open-source module that extends the stock Samsung Camera zoom slider up to 1000x.

How it works: Camera shoots and processes at its own max, then the module crops the processed JPEG around the same center as the enlarged preview. So it's digital zoom, not magic optics. Detail past the stock limit depends on what the sensor resolved. Output is capped at 2048 px on the long edge.

Requirements: rooted phone + Vector, rear camera, Photo mode, JPEG (HEIF off).

Samples: [stock max] vs [2x on AlmaZoom slider] — (attach photos)

Download / source: https://github.com/zz5644934-design/almazoom
Tested on: Galaxy S25 Ultra, Camera 17.0.00.43. Other devices untested, so I'd love test reports (model, Android and Camera version, Vector log lines).

## Target: r/LSPosed, r/Magisk, r/androidroot, r/AndroidMods

**Title:** [Module] AlmaZoom: 1000x zoom slider for Samsung Camera (Vector)

**Body:** Short technical version: hooks the zoom slider range and crops the processed JPEG before Gallery registration. Scope: com.sec.android.app.camera. Builds for One UI 8.0 / 9 and a Xiaomi Camera beta. Source and APKs: https://github.com/zz5644934-design/almazoom . Feedback and logs welcome in Issues.

## Comment reply template
Yes, it's a digital crop of the processed JPEG, not extra optics. The point is a bigger usable slider range and framing at very long distances. Happy to post more samples.
