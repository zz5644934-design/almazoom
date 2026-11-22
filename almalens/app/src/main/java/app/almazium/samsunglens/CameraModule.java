package app.almazium.samsunglens;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.HorizontalScrollView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static de.robv.android.xposed.XposedHelpers.findMethodBestMatch;
import static de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getLongField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public final class CameraModule implements IXposedHookLoadPackage {
    private static final String PACKAGE = "com.sec.android.app.camera";
    private static final String GALLERY = "com.sec.android.gallery3d";
    private static final String ZOOM_VIEW = PACKAGE + ".layer.keyscreen.zoom.ZoomView";
    private static final String SCROLLER_16 = PACKAGE + ".layer.keyscreen.zoom.slider.ZoomSliderView";
    private static final String INTERFACES = PACKAGE + ".interfaces.";
    private static final String ENGINE = PACKAGE + ".engine.";
    private boolean camera16;
    private Object previewing16;
    private SamsungPreviewSurface surfaceZoom16;

    private String symbol(String obfuscated, String named) {
        return camera16 ? named : obfuscated;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Object shootingModeProvider(Activity activity) {
        return getObjectField(activity, symbol("q", "mShootingModeProvider"));
    }

    private Object cameraSettingsOf(Activity activity) {
        return getObjectField(activity, symbol("j", "mCameraSettings"));
    }

    private boolean isPhotoMode(Object provider) {
        return "SHOOTING_MODE_PHOTO".equals(name(callStaticMethod(modeMapping,
                symbol("i", "getCommandId"), getIntField(provider, symbol("d", "mCurrentShootingModeId")))));
    }

    /** Camera facing, or -1 when the setting cannot be read. */
    private int facing(Object settings) {
        return asInt(callMethod(settings, symbol("f", "getCameraFacing")), -1);
    }

    private Object binding(View zoom) {
        return getObjectField(zoom, symbol("a", "mViewBinding"));
    }

    private Object scroller(View zoom) {
        return camera16 ? getObjectField(binding(zoom), "h") : getObjectField(zoom, "e");
    }

    private void setZoom(Object settings, int zoom) {
        Object key = getStaticObjectField(setting, "ZOOM_VALUE");
        if (camera16) callMethod(settings, "set", key, zoom);
        else callMethod(settings, "V", zoom, key);
    }

    private static final int UNIT = 1000;
    private static final int LIMIT = ZoomMath.LIMIT;
    private static final long LENS_CAMERA_VERSION = 1700043000L;
    /** 60 fps preview is on by default; the range keeps auto exposure free, see PreviewFrameRate. */
    private static final String FAST_PREVIEW = "high_fps_preview";
    private static final String LEGACY_FAST_PREVIEW = "experimental_fast_preview";
    private static final String OPTIONS = "almazium_lens_options";
    private static final String EXTEND_ZOOM = "extend_zoom";
    private static final int MAX_WHEEL_FAULTS = 5;
    private static final long WHEEL_RETRY_MS = 2000;
    private static final long WHEEL_FAULT_RESET_MS = 60_000;
    private static final long ACTIVE_POLL_MS = 220;
    private static final long IDLE_POLL_MS = 400;
    private static final long SHUTTER_LOCK_MS = 1500;


    private final Map<Long, CropShot> shots = new ConcurrentHashMap<>();
    private final Map<File, Long> postOutputs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService galleryWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "AlmaZoom gallery");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadLocal<Float> shutterCrop = new ThreadLocal<>();
    /** Fallback for the case when createCaptureInfo runs on another thread than requestCapture. */
    private volatile float lockedFactor = 1f;
    private volatile long lockedAt;
    private Class<?> setting;
    private Class<?> modeMapping;
    private Class<?> resolutionMapping;
    private Object sliderTick, majorTick;
    private boolean photoHooksAttempted;
    private boolean photoHookReady;
    private boolean wheelHookReady;
    private volatile Panel current;
    /** Digital zoom the user had when the camera went to the background (for example to look at a photo). */
    private volatile SavedZoom savedZoom;
    private static final long RESTORE_ZOOM_MS = 10 * 60 * 1000L;
    private static final long RESTORE_WAIT_MS = 6000L;

    private record SavedZoom(int ratio, int base, long at) {}


    private static final class CropShot {
        final float factor;
        final long expires;
        final boolean finalPostProcessing;

        CropShot(float factor, long expires, boolean finalPostProcessing) {
            this.factor = factor;
            this.expires = expires;
            this.finalPostProcessing = finalPostProcessing;
        }
    }
    private record DigitalZoom(int ratio, int base) {
        float factor() { return ZoomMath.cropFactor(ratio, base); }
    }

    private static void verifyProfile(ClassLoader loader, Class<?> camera) throws ReflectiveOperationException {
        Class<?> zoom = findClass(ZOOM_VIEW, loader);
        Class<?> scroll = findClass("y9.g", loader);
        Class<?> capture = findClass("q8.l", loader);
        camera.getDeclaredField("j");
        camera.getDeclaredField("f");
        camera.getDeclaredField("q");
        camera.getDeclaredMethod("F");
        zoom.getDeclaredField("a");
        zoom.getDeclaredField("b");
        zoom.getDeclaredField("e");
        scroll.getDeclaredMethod("c", int.class);
        scroll.getDeclaredMethod("onScrollChange", View.class, int.class, int.class, int.class, int.class);
        scroll.getDeclaredMethod("getSliderLayoutVisibility");
        capture.getDeclaredMethod("m");
        findClass("q8.m0", loader).getDeclaredMethod("i0", capture);
        Class<?> saveTask = findClass("q8.v", loader);
        saveTask.getDeclaredMethod("g", ContentValues.class);
        findClass("q8.x", loader).getDeclaredField("g");
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (GALLERY.equals(pkg.packageName)) {
            if (GALLERY.equals(pkg.processName)) GalleryHooks.install(pkg.classLoader);
            return;
        }
        if (!PACKAGE.equals(pkg.packageName) || !PACKAGE.equals(pkg.processName)) return;
        ClassLoader loader = pkg.classLoader;
        try {
            Class<?> slider16 = findClassIfExists(SCROLLER_16, loader);
            Class<?> key16 = findClassIfExists(INTERFACES + "CameraSettings$Key", loader);
            camera16 = slider16 != null && key16 != null;
            setting = camera16 ? key16 : findClass("l9.f0", loader);
            modeMapping = findClass(symbol("te.l0", PACKAGE + ".util.CameraShootingMode"), loader);
            resolutionMapping = findClass(symbol("te.c0", INTERFACES + "Resolution"), loader);
            Class<?> camera = findClass(PACKAGE + ".Camera", loader);
            if (camera16) {
                camera.getDeclaredMethod("getCameraSettings");
                camera.getDeclaredMethod("getLayerManager");
                Class<?> zoom = findClass(ZOOM_VIEW, loader);
                zoom.getDeclaredField("mViewBinding");
                zoom.getDeclaredMethod("showZoomSlider", int.class);
                previewing16 = getStaticObjectField(findClass(ENGINE + "interfaces.Engine$State", loader), "PREVIEWING");
                try {
                    surfaceZoom16 = new SamsungPreviewSurface();
                } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
                    XposedBridge.log("AlmaZoom PREVIEW_SURFACE_UNAVAILABLE " + unavailable);
                }
            } else {
                verifyProfile(loader, camera);
            }
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " CAMERA_PROFILE_READY "
                    + (camera16 ? "16" : "17"));
            try {
                Class<?> haptic = findClass(symbol("l9.o", INTERFACES + "CameraContext$HapticPattern"), loader);
                sliderTick = getStaticObjectField(haptic, "SLIDER");
                majorTick = getStaticObjectField(haptic, "ZOOM_SLIDER_LARGE_TICK");
                findMethodBestMatch(camera, symbol("o0", "playHaptic"), haptic);
            } catch (LinkageError | RuntimeException unavailable) {
                sliderTick = null;
                majorTick = null;
            }
            findAndHookMethod(camera, "onResume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Activity activity = (Activity) p.thisObject;
                    if (activity.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode()
                            != LENS_CAMERA_VERSION) return;
                    if (!photoHooksAttempted) {
                        photoHooksAttempted = true;
                        installPhotoHooks(loader);
                        installWheelHooks(loader);
                        // 0.16.0: the focus ring and the 60 fps preview are not installed (zoom only)
                    }
                    if (!photoHookReady || !wheelHookReady) return;
                    if (current != null) current.stop();
                    Panel panel = new Panel(activity);
                    current = panel;
                    panel.handler.post(panel);
                }
            });
            findAndHookMethod(camera, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || panel.activity != p.thisObject) return;
                    DigitalZoom zoom = panel.digitalZoom;
                    if (zoom != null) {
                        savedZoom = new SavedZoom(zoom.ratio(), zoom.base(), SystemClock.elapsedRealtime());
                    }
                    panel.stop();
                    current = null;
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " CAMERA_PROFILE_UNAVAILABLE " + error);
        }
    }

    private boolean canBoostPreview() {
        Panel panel = current;
        if (panel == null) return false;
        Activity activity = panel.activity;
        if (!activity.getSharedPreferences(OPTIONS, 0).getBoolean(FAST_PREVIEW, true)) return false;
        Object provider = shootingModeProvider(activity);
        Object cameraSettings = cameraSettingsOf(activity);
        return provider != null && cameraSettings != null
                && isPhotoMode(provider)
                && facing(cameraSettings) == 0
                && ordinaryResolution(cameraSettings)
                && readSetting(cameraSettings, "MOTION_PHOTO") == 0
                && readSetting(cameraSettings, "QUICK_TAKE_RECORDING_STATUS") == 0;
    }

    private void installFocusHooks(ClassLoader loader) {
        try {
            Class<?> aeAfGroup = findClass(
                    "com.sec.android.app.camera.layer.previewoverlay.aeaf.AeAfGroup", loader);
            findAndHookMethod(aeAfGroup, "m", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || p.hasThrowable()) return;
                    try {
                        View circle = (View) callMethod(p.thisObject, "getAeAfCircle");
                        panel.colorFocusRing(circle);
                    } catch (Throwable error) {
                        log(error);
                    }
                }
            });
        } catch (Throwable error) {
            log(error);
        }
    }

    private void installWheelHooks(ClassLoader loader) {
        try {
            Class<?> scroller = findClass(symbol("y9.g", SCROLLER_16), loader);
            findAndHookMethod(scroller, "onScrollChange", View.class, int.class, int.class,
                    int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null) return;
                    try {
                        int x = (Integer) p.args[1];
                        int previous = (Integer) p.args[3];
                        panel.animateLensScroll(p.thisObject, x - previous);
                        if (panel.scrollZoom(p.thisObject, x, previous)) {
                            p.setResult(null);
                        }
                    } catch (Throwable error) {
                        ZoomWheel wheel = panel.wheel;
                        if (wheel != null && p.thisObject == wheel.scroll) {
                            int limit = wheel.nativeEnd();
                            if (limit >= 0) {
                                p.args[1] = Math.min((Integer) p.args[1], limit);
                                p.args[3] = Math.min((Integer) p.args[3], limit);
                            }
                        }
                        panel.recoverWheel(error);
                    }
                }
            });
            findAndHookMethod(scroller, symbol("f", "initializeSliderView"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || p.hasThrowable() || panel.zoomView == null) return;
                    try {
                        if (scroller(panel.zoomView) == p.thisObject) panel.updateWheel();
                    } catch (Throwable error) {
                        panel.recoverWheel(error);
                    }
                }
            });
            findAndHookMethod(scroller, symbol("c", "getScrollValueByZoomLevel"), int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || p.hasThrowable()) return;
                    DigitalZoom selected = panel.digitalZoom;
                    ZoomWheel wheel = panel.wheel;
                    if (selected == null || wheel == null || wheel.scroll != p.thisObject
                            || Math.abs((Integer) p.args[0] - selected.base()) > 250) return;
                    if (wheel.ready()) p.setResult(wheel.positionAt(selected.ratio()));
                    else panel.alignWheel = true;
                }
            });
            findAndHookMethod(findClass(ZOOM_VIEW, loader), symbol("F2", "showZoomSlider"), int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || panel.zoomView != p.thisObject || panel.wheel == null
                            || panel.digitalZoom == null) return;
                    ZoomWheel wheel = panel.wheel;
                    p.setObjectExtra("almaOpeningWheel", wheel);
                    p.setObjectExtra("almaPreviousPositioning", wheel.positioning);
                    wheel.positioning = true;
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaOpeningWheel") instanceof ZoomWheel wheel) {
                        wheel.positioning = Boolean.TRUE.equals(p.getObjectExtra("almaPreviousPositioning"));
                    }
                }
            });
            wheelHookReady = true;
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " ZOOM_WHEEL_HOOKS_READY");
        } catch (Throwable error) {
            log(error);
        }
    }

    private void installPhotoHooks(ClassLoader loader) {
        try {
            Class<?> captureInfo = findClass(symbol("q8.l", ENGINE + "interfaces.CaptureInfo"), loader);
            Class<?> controller = findClass(symbol("q8.m0", ENGINE + "capture.SingleCaptureController"), loader);
            Class<?> saveTask = findClass(symbol("q8.v", ENGINE + "capture.PictureProcessor$PictureSavingTask"), loader);
            Class<?> captureManager = findClass(symbol("q8.o", ENGINE + "capture.CaptureManagerImpl"), loader);
            Class<?> input = findClass(symbol("l9.p", INTERFACES + "CameraContext$InputType"), loader);
            Class<?> captureType = findClass(symbol("z8.g0", ENGINE + "interfaces.CaptureManager$CaptureType"), loader);
            findAndHookMethod(captureManager, symbol("x", "requestCapture"), input, captureType, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("almaPreviousShutterCrop", shutterCrop.get());
                    float factor = "SINGLE_CAPTURE".equals(name(p.args[1])) ? currentCaptureFactor() : 1f;
                    shutterCrop.set(factor);
                    lockedFactor = factor;
                    lockedAt = SystemClock.elapsedRealtime();
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaPreviousShutterCrop") instanceof Float old) {
                        shutterCrop.set(old);
                    } else {
                        shutterCrop.remove();
                    }
                }
            });
            findAndHookMethod(captureManager, symbol("e", "createCaptureInfo"), input, captureType, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!"SINGLE_CAPTURE".equals(name(p.args[1]))) return;
                    Float locked = shutterCrop.get();
                    float factor = locked != null ? locked
                            : SystemClock.elapsedRealtime() - lockedAt < SHUTTER_LOCK_MS ? lockedFactor
                            : currentCaptureFactor();
                    if (factor > 1f) p.setObjectExtra("almaZoomFactor", factor);
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    if (p.getObjectExtra("almaZoomFactor") instanceof Float factor) {
                        rememberShot(p.getResult(), factor);
                    }
                }
            });
            findAndHookMethod(controller, symbol("i0", "requestSingleCapture"), captureInfo, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    rememberShot(p.args[0], currentCaptureFactor());
                }
            });
            installPostProcessingHooks(loader, captureManager, captureType);
            findAndHookMethod(saveTask, symbol("g", "insertToDb"), ContentValues.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (shots.isEmpty()) return;
                    try {
                        Object image = jpegSaveImage(p.thisObject);
                        if (image == null) return;
                        long stamp = getLongField(image, symbol("g", "mDateTaken"));
                        CropShot shot = pendingShot(stamp);
                        trace(stamp, "INSERT_TO_DB", "pending=" + (shot != null)
                                + " finalPostProcessing=" + (shot != null && shot.finalPostProcessing));
                        if (shot == null || (camera16 && shot.finalPostProcessing)) return;
                        File file = new File((String) callMethod(p.thisObject, symbol("d", "getFilePath")));
                        JpegCrop.Result crop = cropForPublication(stamp, file, "SAVE_TASK");
                        if (crop == null) return;
                        ContentValues values = (ContentValues) p.args[0];
                        values.put("width", crop.width());
                        values.put("height", crop.height());
                        p.setObjectExtra("almaPublishedStamp", stamp);
                    } catch (Throwable error) {
                        log(error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaPublishedStamp") instanceof Long stamp) {
                        trace(stamp, "SAVE_TASK_RETURNED", "ok=" + !p.hasThrowable());
                        hideProcessing();
                    }
                }
            });
            photoHookReady = true;
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " JPEG_HOOKS_READY");
        } catch (Throwable error) {
            log(error);
        }
    }

    private void installPostProcessingHooks(ClassLoader loader, Class<?> manager, Class<?> captureType) {
        Class<?> scanner = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                + "PostSavingModuleScanFile", loader);
        Class<?> validation = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                + "PostSavingModuleValidationCheck", loader);
        Class<?> savingInfo = findClass("com.samsung.android.camera.core2.container.SavingInfoContainer"
                + (camera16 ? "" : "$SavingInfo"), loader);
        findAndHookMethod(manager, symbol("f", "getOutputFilePath"), captureType, long.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                long stamp = (Long) p.args[1];
                if (pendingShot(stamp) == null || p.hasThrowable()) return;
                File output = finalOutputFile(p.getResult());
                trace(stamp, "OUTPUT_PATH", name(p.args[0]) + " -> " + leaf(p.getResult()));
                if (output != null) {
                    postOutputs.put(output, stamp);
                    ProcessingMarker.set(output);
                    trace(stamp, "FINAL_PATH_REGISTERED", "");
                }
            }
        });
        if (!camera16) {
            // Samsung writes a draft (<name>_temp.jpg in a private folder) right after the shutter and the gallery shows it
            // until the processed photo replaces it. Crop it right away with the same factor, so the framing does not
            // jump from 100x to the zoom that was actually used.
            Class<?> draftWriter = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                    + "PostSavingModuleDraftWrite", loader);
            findAndHookMethod(draftWriter, "runModule", savingInfo, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try {
                        Object info = p.args[0];
                        if (!"MAIN".equals(name(getObjectField(info, "a")))) return;
                        File finalFile = (File) getObjectField(info, "c");
                        Long stamp = finalFile == null ? null : postOutputs.get(finalFile);
                        Object draft = getObjectField(info, "d");
                        trace(stamp == null ? 0L : stamp, "DRAFT_WRITTEN", "final=" + leaf(finalFile)
                                + " draft=" + leaf(draft) + " matched=" + (stamp != null));
                        if (stamp == null || !(draft instanceof java.nio.file.Path path)) return;
                        cropDraft(stamp, path.toFile());
                    } catch (Throwable error) {
                        log(error);
                    }
                }
            });
        }
        findAndHookMethod(validation, "runModule", savingInfo, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (postOutputs.isEmpty()) return;
                Object info = p.args[0];
                if (!camera16 && !"MAIN".equals(name(getObjectField(info, "a")))) return;
                File file = (File) getObjectField(info, "c");
                Long stamp = file == null ? null : postOutputs.get(file);
                if (stamp != null) setAdditionalInstanceField(info, "almaFinalStamp", stamp);
            }
        });
        if (camera16) {
            Class<?> writer = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                    + "PostSavingModuleImageWrite", loader);
            findAndHookMethod(writer, "runModule", savingInfo, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || postOutputs.isEmpty()
                            || getBooleanField(p.thisObject, "mIsDraft")) return;
                    Object extra = getObjectField(p.thisObject, "mExtraSavingInfoContainer");
                    Object info = extra == null ? p.args[0] : extra;
                    File file = (File) getObjectField(info, "c");
                    if (file == null) return;
                    Long stamp = postOutputs.get(file);
                    if (stamp == null) {
                        Object validated = getAdditionalInstanceField(info, "almaFinalStamp");
                        if (validated instanceof Long original) stamp = original;
                    }
                    if (stamp == null) return;
                    JpegCrop.Result crop = cropForPublication(stamp, file, "SAMSUNG_FINAL_WRITE");
                    if (crop == null) return;
                    if (getObjectField(info, "g") instanceof ContentValues values) {
                        values.put("width", crop.width());
                        values.put("height", crop.height());
                        values.put("_size", file.length());
                    }
                    setAdditionalInstanceField(info, "almaCroppedBeforeScan", stamp);
                }
            });
        }
        // This scanner runs after the final ImageWrite and before Galaxy Gallery is told to swap out the draft.
        findAndHookMethod(scanner, "runModule", savingInfo, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shots.isEmpty() && postOutputs.isEmpty()) return;
                if (camera16 && getObjectField(p.thisObject, "mExtraSavingInfoContainer") != null) return;
                Object info = p.args[0];
                if (!camera16 && !"MAIN".equals(name(getObjectField(info, "a")))) return;
                File file = (File) getObjectField(info, "c");
                if (file == null) return;
                Long stamp = postOutputs.remove(file);
                trace(stamp == null ? 0L : stamp, "SCAN_FILE", leaf(file) + " matched=" + (stamp != null));
                Object croppedStamp = removeAdditionalInstanceField(info, "almaCroppedBeforeScan");
                if (croppedStamp instanceof Long cropped) {
                    p.setObjectExtra("almaPublishedStamp", cropped);
                    return;
                }
                Object validatedStamp = removeAdditionalInstanceField(info, "almaFinalStamp");
                if (stamp == null && validatedStamp instanceof Long original) stamp = original;
                if (stamp == null) return;
                JpegCrop.Result crop = cropForPublication(stamp, file, "SAMSUNG_FINAL_SCAN");
                if (crop != null) p.setObjectExtra("almaPublishedStamp", stamp);
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("almaPublishedStamp") instanceof Long stamp) {
                    String result = camera16
                            ? "scanComplete=" + getBooleanField(p.args[0], "k")
                            : "uriPresent=" + (getObjectField(p.args[0], "k") instanceof Uri);
                    trace(stamp, "SAMSUNG_SCAN_RETURNED", result + " ok=" + !p.hasThrowable());
                    if (getObjectField(p.args[0], "c") instanceof File published) ProcessingMarker.clear(published);
                    hideProcessing();
                }
            }
        });
    }

    static File finalOutputFile(Object result) {
        Object output = result instanceof List<?> outputs && !outputs.isEmpty() ? outputs.get(0) : result;
        if (!(output instanceof String path)) return null;
        File file = new File(path);
        return file.isAbsolute() ? file : null;
    }

    private JpegCrop.Result cropForPublication(long stamp, File file, String route) {
        CropShot shot = pendingShot(stamp);
        if (shot == null || !shots.remove(stamp, shot)) return null;
        long started = SystemClock.elapsedRealtime();
        try {
            JpegCrop.Result crop = JpegCrop.replaceProcessed(file, shot.factor);
            if (crop == null) {
                trace(stamp, "CROP_SKIPPED", "route=" + route + " bytes=" + file.length());
                ProcessingMarker.clear(file);
                hideProcessing();
                return null;
            }
            trace(stamp, "CROPPED_BEFORE_PUBLICATION", "route=" + route + " factor=" + shot.factor
                    + " input=" + crop.inputWidth() + "x" + crop.inputHeight()
                    + " output=" + crop.width() + "x" + crop.height()
                    + " beforeBytes=" + crop.inputBytes() + " finalBytes=" + file.length()
                    + " ms=" + (SystemClock.elapsedRealtime() - started));
            return crop;
        } catch (Exception error) {
            trace(stamp, "CROP_FAILED", "route=" + route + " error=" + error.getClass().getSimpleName()
                    + " detail=" + error.getMessage());
            ProcessingMarker.clear(file);
            hideProcessing();
            return null;
        }
    }

    private void showProcessing() {
        Panel panel = current;
        if (panel != null) panel.handler.post(panel::showProcessing);
    }

    private void hideProcessing() {
        Panel panel = current;
        if (panel != null) panel.handler.post(panel::hideProcessing);
    }

    private static String leaf(Object path) {
        String text = String.valueOf(path);
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    /** Crops the draft but keeps the shot registered: the processed photo still has to be cropped later. */
    private void cropDraft(long stamp, File draft) {
        CropShot shot = pendingShot(stamp);
        if (shot == null) return;
        long started = SystemClock.elapsedRealtime();
        try {
            JpegCrop.Result crop = JpegCrop.replaceProcessed(draft, shot.factor);
            if (crop == null) {
                trace(stamp, "DRAFT_CROP_SKIPPED", "bytes=" + draft.length());
                return;
            }
            trace(stamp, "DRAFT_CROPPED", "factor=" + shot.factor + " input=" + crop.inputWidth() + "x"
                    + crop.inputHeight() + " output=" + crop.width() + "x" + crop.height()
                    + " ms=" + (SystemClock.elapsedRealtime() - started));
        } catch (Exception error) {
            trace(stamp, "DRAFT_CROP_FAILED", error.getClass().getSimpleName() + " " + error.getMessage());
        }
    }

    private float currentCaptureFactor() {
        Panel panel = current;
        if (panel == null) return 1f;
        try {
            return panel.captureFactor();
        } catch (Throwable error) {
            log(error);
            return 1f;
        }
    }

    private Object jpegSaveImage(Object task) {
        Object image = getObjectField(task, symbol("b", "mPictureSavingInfo"));
        return "SINGLE_CAPTURE".equals(name(getObjectField(image, symbol("f", "mCaptureType"))))
                && "JPEG".equals(name(getObjectField(image, symbol("b", "mImageFileFormat"))))
                ? image : null;
    }

    private CropShot pendingShot(long stamp) {
        CropShot shot = shots.get(stamp);
        return shot != null && shot.expires >= SystemClock.elapsedRealtime() ? shot : null;
    }

    private void rememberShot(Object info, float factor) {
        if (info == null || factor <= 1f) return;
        try {
            if (!"SINGLE_CAPTURE".equals(name(callMethod(info, symbol("b", "getCaptureType"))))) return;
            long stamp = ((Number) callMethod(info, symbol("m", "getTakePictureTime"))).longValue();
            if (stamp <= 0) return;
            long now = SystemClock.elapsedRealtime();
            shots.entrySet().removeIf(entry -> entry.getValue().expires < now);
            postOutputs.entrySet().removeIf(entry -> !shots.containsKey(entry.getValue()));
            String route = name(callMethod(info, symbol("n", "getTakePictureType")));
            CropShot shot = new CropShot(factor, now + 180000, "PROCESSING_POST".equals(route));
            if (shots.putIfAbsent(stamp, shot) == null) {
                trace(stamp, "SHUTTER", "factor=" + factor + " route=" + route);
                showProcessing();
                galleryWorker.schedule(() -> {
                    if (shots.get(stamp) == shot) trace(stamp, "WAITING_FOR_JPEG", "elapsedMs=8000");
                }, 8, TimeUnit.SECONDS);
            }
        } catch (Throwable error) {
            log(error);
        }
    }

    private static String name(Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : "";
    }

    private static void trace(long stamp, String stage, String details) {
        XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " shot=" + Long.toUnsignedString(stamp, 36)
                + " " + stage + (details.isEmpty() ? "" : " " + details));
    }

    private static void log(Throwable error) {
        XposedBridge.log("AlmaZoom: " + error);
    }

    private int readSetting(Object settings, String key) {
        return asInt(callMethod(settings, symbol("d", "get"), getStaticObjectField(setting, key)), -1);
    }

    private boolean ordinaryResolution(Object cameraSettings) {
        if (cameraSettings == null) return false;
        int resolution = readSetting(cameraSettings, "BACK_CAMERA_RESOLUTION");
        Size size = camera16
                ? (Size) callMethod(callStaticMethod(resolutionMapping, "getResolution", resolution), "getSize")
                : (Size) callStaticMethod(resolutionMapping, "v0", resolution);
        return size != null && size.getWidth() > 0 && size.getHeight() > 0
                && !JpegCrop.isHighResolution(size.getWidth(), size.getHeight());
    }

    private final class Panel implements Runnable {
        final Activity activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final StockZoomLabels stockLabels = new StockZoomLabels();
        boolean reportedMissingPreviewBuffer;
        int wheelFaults;
        long wheelRetryAt, lastWheelFault;
        boolean active;
        SavedZoom restore;
        long restoreUntil;
        ProcessingOverlay processing;
        final ToolbarIcon toolbar;
        final ZoomGlint zoomGlint = new ZoomGlint();
        LiquidZoomGlass zoomGlass;
        FocusRing focusRing;
        AlertDialog dialog;
        boolean glassUnavailable;
        float originalRefresh, requestedRefresh;
        volatile DigitalZoom digitalZoom;
        final SharedPreferences options;
        ZoomWheel wheel;
        boolean alignWheel, wheelWasVisible;
        final Runnable renderDigital = this::renderDigitalFrame;
        boolean digitalFrameQueued;
        View zoomView;
        Object settings, engine;
        PreviewCrop previewCrop;
        volatile float visibleCropFactor = 1f;
        int lastZoom;
        long pendingUntil, unfocusedSince;
        String lastError;

        Panel(Activity activity) {
            this.activity = activity;
            options = activity.getSharedPreferences(OPTIONS, 0);
            if (options.contains(LEGACY_FAST_PREVIEW)) options.edit().remove(LEGACY_FAST_PREVIEW).apply();
            toolbar = new ToolbarIcon(activity, this::toggleZoom, this::showLensSettings);
            SavedZoom saved = savedZoom;
            long now = SystemClock.elapsedRealtime();
            if (saved != null && now - saved.at() <= RESTORE_ZOOM_MS) {
                restore = saved;
                restoreUntil = now + RESTORE_WAIT_MS;
            }
        }

        void showProcessing() {
            try {
                if (processing == null) processing = new ProcessingOverlay(activity);
                processing.show();
            } catch (Throwable error) {
                log(error);
            }
        }

        void hideProcessing() {
            if (processing != null) processing.hide();
        }

        void colorFocusRing(View circle) {
            Object provider = shootingModeProvider(activity);
            Object cameraSettings = cameraSettingsOf(activity);
            if (circle == null || circle.getVisibility() != View.VISIBLE
                    || circle.getRootView() != activity.getWindow().getDecorView()
                    || provider == null || cameraSettings == null || !activity.hasWindowFocus()
                    || !isPhotoMode(provider)
                    || facing(cameraSettings) != 0
                    || readSetting(cameraSettings, "QUICK_TAKE_RECORDING_STATUS") != 0) return;
            Drawable background = circle.getBackground();
            if (background == null) return;
            if (!(background instanceof FocusRing)) {
                if (focusRing != null) focusRing.restore();
                focusRing = new FocusRing(circle, background);
                circle.setBackground(focusRing);
            }
        }

        @Override public void run() {
            if (current != this) return;
            try {
                update();
                lastError = null;
            } catch (Throwable error) {
                recoverWheel(error);
                hideLensEffects();
                toolbar.hide();
                String description = error.toString();
                if (!description.equals(lastError)) log(error);
                lastError = description;
            }
            if (current == this) handler.postDelayed(this, active ? ACTIVE_POLL_MS : IDLE_POLL_MS);
        }

        private void update() throws Exception {
            if (activity.isFinishing() || activity.isDestroyed()) { hide(); return; }
            active = false;
            Object provider = shootingModeProvider(activity);
            settings = cameraSettingsOf(activity);
            if (provider == null || settings == null) { hide(); return; }
            if (!isPhotoMode(provider)
                    || facing(settings) != 0
                    || readSetting(settings, "QUICK_TAKE_RECORDING_STATUS") != 0
                    || (focusLost() && (dialog == null || !dialog.isShowing()))) {
                hide();
                return;
            }

            active = true;
            Object layers = callMethod(activity, symbol("F", "getLayerManager"));
            if (layers == null) { hide(); return; }
            Object keyScreen = callMethod(layers, "getKeyScreenLayerManager");
            if (keyScreen == null) { hide(); return; }
            toolbar.update(options.getBoolean(EXTEND_ZOOM, true));
            Object zoom = callMethod(keyScreen, "getZoomManager");
            if (!(zoom instanceof View view) || !ZOOM_VIEW.equals(view.getClass().getName())) {
                hideZoom();
                return;
            }
            if (zoomView != view) {
                resetCrop();
                removeWheel();
                hideGlass();
                zoomView = view;
            }
            Object binding = binding(zoomView);
            if (binding == null) { hide(); return; }
            Object presenter = getObjectField(zoomView, symbol("b", "mPresenter"));
            if (presenter == null) { hide(); return; }
            engine = getObjectField(presenter, symbol("b", "mEngine"));
            if (engine == null) { hide(); return; }
            // The capture request can be queued after preview has entered its capture state.
            boolean previewing = camera16 ? Boolean.TRUE.equals(callMethod(engine, "isCurrentState", previewing16))
                    : "PREVIEWING".equals(name(getObjectField(engine, "B")));
            if (!previewing) return;
            if (!view.isShown() && digitalZoom == null) { hideZoom(); return; }
            refreshLensEffects(binding);
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return;
            }
            lastZoom = readSetting(settings, "ZOOM_VALUE");
            updateWheel();
            restoreDigitalZoom();
            if (!digitalFrameQueued) updateDigital(binding);
            boolean wheelVisible = wheel != null && wheel.scroll.isShown()
                    && sliderVisible(wheel.scroll);
            DigitalZoom selected = digitalZoom;
            if (selected != null && wheelVisible && (alignWheel || !wheelWasVisible) && wheel.ready()) {
                wheel.showRatio(selected.ratio());
                alignWheel = false;
            }
            wheelWasVisible = wheelVisible;
        }

        private void refreshLensEffects(Object binding) {
            if (!ordinaryResolution(settings)) {
                hideLensEffects();
                return;
            }
            try {
                requestSmoothDisplay();
                updateGlass(binding);
            } catch (RuntimeException | LinkageError failure) {
                if (!glassUnavailable) log(failure);
                glassUnavailable = true;
                hideGlass();
            }
        }

        private void animateLensScroll(Object widget, int distance) {
            if (zoomView == null || distance == 0 || (wheel != null && wheel.positioning)) return;
            try {
                if (widget != scroller(zoomView) || !(widget instanceof HorizontalScrollView scroll)
                        || !sliderVisible(scroll) || getBooleanField(widget, "m") || !ordinaryResolution(settings)) return;
                updateGlass(binding(zoomView));
            } catch (RuntimeException | LinkageError failure) {
                if (!glassUnavailable) log(failure);
                glassUnavailable = true;
                hideGlass();
            }
        }

        private void hideLensEffects() {
            hideGlass();
            restoreDisplay();
            if (focusRing != null) {
                focusRing.restore();
                focusRing = null;
            }
        }

        private void requestSmoothDisplay() {
            if (requestedRefresh > 0f) return;
            Display display = activity.getDisplay();
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            if (display == null || attributes.preferredDisplayModeId != 0) return;
            Display.Mode currentMode = display.getMode();
            float best = 0f;
            for (Display.Mode mode : display.getSupportedModes()) {
                float rate = mode.getRefreshRate();
                if (mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                        && mode.getPhysicalHeight() == currentMode.getPhysicalHeight()
                        && rate <= 120.1f && rate > best) best = rate;
            }
            if (best < 90f || attributes.preferredRefreshRate >= best) return;
            originalRefresh = attributes.preferredRefreshRate;
            requestedRefresh = best;
            attributes.preferredRefreshRate = best;
            activity.getWindow().setAttributes(attributes);
        }

        private void restoreDisplay() {
            if (requestedRefresh == 0f) return;
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            if (attributes.preferredRefreshRate == requestedRefresh) {
                attributes.preferredRefreshRate = originalRefresh;
                activity.getWindow().setAttributes(attributes);
            }
            requestedRefresh = 0f;
        }

        /** The liquid glass and the glint on the zoom scale are off in 0.16.0 (zoom only). */
        private static final boolean GLASS_ENABLED = false;

        private void updateGlass(Object binding) {
            if (!GLASS_ENABLED) return;
            Object candidate = getObjectField(binding, "i");
            if (!(candidate instanceof View bar) || !bar.isShown() || bar.getAlpha() < 0.05f) {
                hideGlass();
                return;
            }
            if (Build.VERSION.SDK_INT < 33 || glassUnavailable) return;
            try {
                if (zoomGlass == null) zoomGlass = new LiquidZoomGlass(handler, zoomGlint);
                zoomGlass.show(bar, findPreview());
            } catch (RuntimeException | LinkageError error) {
                if (zoomGlass != null) zoomGlass.stop();
                glassUnavailable = true;
                log(error);
            }
        }

        private void hideGlass() {
            zoomGlint.clear();
            if (zoomGlass != null) zoomGlass.stop();
        }

        private boolean digitalConfigured() {
            return photoHookReady && options.getBoolean(EXTEND_ZOOM, true)
                    && ordinaryResolution(settings) && readSetting(settings, "HEIF") == 0
                    && readSetting(settings, "MOTION_PHOTO") == 0;
        }

        private float captureFactor() {
            DigitalZoom selected = digitalZoom;
            if (selected == null || !options.getBoolean(EXTEND_ZOOM, true)) return 1f;
            Object liveSettings = cameraSettingsOf(activity);
            return ordinaryResolution(liveSettings) && facing(liveSettings) == 0
                    && readSetting(liveSettings, "HEIF") == 0
                    && readSetting(liveSettings, "MOTION_PHOTO") == 0
                    && Math.abs(readSetting(liveSettings, "ZOOM_VALUE") - selected.base()) <= 250
                    ? visibleCropFactor : 1f;
        }

        private void updateWheel() {
            if (wheelFaulted() || !wheelHookReady || engine == null || zoomView == null) return;
            Object candidate = scroller(zoomView);
            if (!(candidate instanceof HorizontalScrollView scroll)) return;
            Object slider = getObjectField(scroll, symbol("g", "mZoomTickSlider"));
            Object positions = getObjectField(scroll, symbol("d", "mCenterPosArray"));
            if (!(slider instanceof View tickView) || !(positions instanceof SparseIntArray nativePositions)) return;
            if (wheel != null && (wheel.scroll != scroll || wheel.ticks != tickView
                    || wheel.nativePositions != nativePositions)) {
                wheel.remove();
                wheel = null;
                alignWheel = digitalZoom != null;
            }
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return;
            }
            if (wheel == null) wheel = new ZoomWheel(scroll, tickView, nativePositions, camera16);
            if (wheel.hardwareMaximum() <= 0 || wheel.hardwareMaximum() >= LIMIT) return;
            View preview = findPreview();
            if (!supportsPreviewCrop(preview)) {
                if (camera16 && preview != null && !reportedMissingPreviewBuffer) {
                    reportedMissingPreviewBuffer = true;
                    XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " PREVIEW_BUFFER_UNAVAILABLE");
                }
                return;
            }
            if (wheel.install() && digitalZoom != null) alignWheel = true;
        }

        /** After the camera comes back to the foreground Samsung keeps its own 100x; put the extended zoom back. */
        private void restoreDigitalZoom() {
            SavedZoom saved = restore;
            if (saved == null) return;
            long now = SystemClock.elapsedRealtime();
            if (digitalZoom != null || now > restoreUntil || wheelFaulted()) {
                restore = null;
                return;
            }
            if (wheel == null || !wheel.ready() || !digitalConfigured()) return;
            int maximum = wheel.hardwareMaximum();
            if (saved.base() != maximum) {
                restore = null;
                return;
            }
            if (Math.abs(readSetting(settings, "ZOOM_VALUE") - maximum) > 250) return; // Samsung has not settled yet
            digitalZoom = new DigitalZoom(saved.ratio(), maximum);
            pendingUntil = now + 1800;
            alignWheel = true;
            restore = null;
            savedZoom = null;
            XposedBridge.log("AlmaZoom zoom restored to " + format(saved.ratio()) + "x");
        }

        private boolean sliderVisible(HorizontalScrollView scroll) {
            return camera16 ? scroll.isShown()
                    : Integer.valueOf(View.VISIBLE).equals(callMethod(scroll, "getSliderLayoutVisibility"));
        }

        private boolean scrollZoom(Object widget, int x, int previous) throws Exception {
            if (wheel == null || widget != wheel.scroll) return false;
            if (wheel.positioning) return true;
            if (wheelFaulted() || !wheel.ready() || x < 0 || !sliderVisible(wheel.scroll)
                    || getBooleanField(widget, symbol("m", "mIsIgnoreScrollEvent"))) return false;
            int end = wheel.nativeEnd();
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return x > end;
            }
            int maximum = wheel.hardwareMaximum();
            if (asInt(callMethod(engine, symbol("B", "getMaxZoomLevel")), -1) != maximum) {
                disableDigitalZoom();
                return x > end;
            }
            int digitalStart = wheel.digitalStart();
            DigitalZoom selected = digitalZoom;
            if (x <= end) {
                if (selected != null) {
                    if (!getBooleanField(widget, symbol("n", "mIsScrolling"))) {
                        alignWheel = true;
                        return true;
                    }
                    resetCrop();
                }
                return false;
            }
            if (selected == null && x <= digitalStart) {
                if (Math.abs(readSetting(settings, "ZOOM_VALUE") - maximum) > 250) {
                    setZoom(settings, maximum);
                    callMethod(zoomView, symbol("N2", "updateZoomValue"), maximum);
                }
                wheel.moveHighlight(x);
                return true;
            }

            int ratio = wheel.ratioAt(x);
            digitalZoom = new DigitalZoom(ratio, maximum);
            if (selected == null || selected.base() != maximum) {
                pendingUntil = SystemClock.elapsedRealtime() + 1800;
                if (Math.abs(readSetting(settings, "ZOOM_VALUE") - maximum) > 250) {
                    setZoom(settings, maximum);
                }
            }
            alignWheel = false;
            wheel.moveHighlight(x);
            if (x != previous) playZoomTick(wheel.hapticTick(x, previous));
            if (!digitalFrameQueued) {
                digitalFrameQueued = true;
                zoomView.postOnAnimation(renderDigital);
            }
            return true;
        }

        private void playZoomTick(int tick) {
            if (tick == 0) return;
            try {
                if (readSetting(settings, "TOUCH_VIBRATIONS") != 1) return;
                Object nativeTick = tick == 2 ? majorTick : sliderTick;
                if (nativeTick != null) {
                    callMethod(activity, symbol("o0", "playHaptic"), nativeTick);
                } else {
                    zoomView.performHapticFeedback(tick == 2
                            ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.CLOCK_TICK);
                }
            } catch (LinkageError | RuntimeException unavailable) {
                // Haptics are optional. An unavailable preference or driver must not interrupt zoom.
            }
        }

        private void renderDigitalFrame() {
            digitalFrameQueued = false;
            if (current != this || zoomView == null || digitalZoom == null) return;
            try {
                Object binding = binding(zoomView);
                lastZoom = readSetting(settings, "ZOOM_VALUE");
                updateDigital(binding);
            } catch (Throwable error) {
                recoverWheel(error);
            }
        }

        private void updateDigital(Object binding) throws Exception {
            DigitalZoom selected = digitalZoom;
            if (selected == null) return;
            if (!digitalConfigured()) {
                resetCrop();
                return;
            }
            if (lastZoom <= 0 || Math.abs(lastZoom - selected.base()) > 250) {
                clearPreviewCrop();
                if (SystemClock.elapsedRealtime() > pendingUntil) resetCrop();
                return;
            }
            float factor = selected.factor();
            if (factor <= 1f) {
                clearPreviewCrop();
                return;
            }
            View preview = findPreview();
            if (preview == null || !applyCrop(preview, factor)) {
                clearPreviewCrop();
                stockLabels.restore();
                return;
            }
            stockLabels.show(binding, selected.base(), format(selected.ratio()),
                    formatCompact(selected.ratio()), camera16);
            visibleCropFactor = factor;
            pendingUntil = 0;
        }

        private boolean focusLost() {
            if (activity.hasWindowFocus()) {
                unfocusedSince = 0;
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            if (unfocusedSince == 0) unfocusedSince = now;
            return now - unfocusedSince >= 450;
        }

        private void toggleZoom() {
            setZoomEnabled(!options.getBoolean(EXTEND_ZOOM, true));
        }

        private void showLensSettings() {
            if (dialog != null && dialog.isShowing()) return;
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(22), dp(8), dp(22), 0);

            Switch extended = new Switch(activity);
            extended.setText(Ui.text("Зум до 1000×", "Zoom up to 1000×"));
            extended.setMinHeight(dp(48));
            extended.setChecked(options.getBoolean(EXTEND_ZOOM, true));
            extended.setOnCheckedChangeListener((button, enabled) -> {
                setZoomEnabled(enabled);
            });
            content.addView(extended, new LinearLayout.LayoutParams(-1, -2));

            dialog = new AlertDialog.Builder(activity).setTitle("AlmaZoom")
                    .setView(content).setPositiveButton(Ui.text("Готово", "Done"), null).create();
            dialog.show();
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        private void setZoomEnabled(boolean enabled) {
            options.edit().putBoolean(EXTEND_ZOOM, enabled).apply();
            try {
                if (!enabled) disableDigitalZoom();
                toolbar.announce(enabled, findPreview());
            } catch (Throwable error) {
                log(error);
                toolbar.announce(enabled, null);
            }
        }

        private View findPreview() {
            Object controller = getObjectField(activity, symbol("f", "mPreviewManager"));
            if (controller == null) return null;
            if (camera16) {
                Object holder = getObjectField(controller, "mPreviewSurfaceManager");
                if (holder == null) return null;
                Object candidate = getObjectField(holder, "mSurfaceView");
                return candidate instanceof SurfaceView view && usable(view) ? view : null;
            }
            Object holder = getObjectField(controller, "g");
            if (holder != null) {
                for (String field : new String[]{"d", "f", "e"}) {
                    Object candidate = getObjectField(holder, field);
                    if (candidate instanceof View view && usable(view)) return view;
                }
            }
            Object primary = getObjectField(controller, "f");
            return primary instanceof View view && usable(view) ? view : null;
        }

        private boolean usable(View view) {
            if (!view.isShown() || view.getWidth() < 20 || view.getHeight() < 20) return false;
            if (view instanceof TextureView texture) return texture.isAvailable();
            return view instanceof SurfaceView surface && surface.getHolder().getSurface().isValid();
        }

        private boolean supportsPreviewCrop(View preview) {
            return camera16 ? surfaceZoom16 != null && preview instanceof SurfaceView surface
                    && surfaceZoom16.supports(surface) : PreviewCrop.supported(preview);
        }

        private boolean applyCrop(View preview, float factor) throws Exception {
            if (!supportsPreviewCrop(preview)) return false;
            if (camera16) return surfaceZoom16.apply((SurfaceView) preview, factor);
            if (previewCrop == null || previewCrop.view != preview) {
                if (previewCrop != null) previewCrop.restore();
                previewCrop = new PreviewCrop(preview);
                XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " PREVIEW_CROP_READY "
                        + preview.getClass().getSimpleName());
            }
            return previewCrop.apply(factor);
        }

        private void resetCrop() {
            if (zoomView != null) zoomView.removeCallbacks(renderDigital);
            digitalFrameQueued = false;
            digitalZoom = null;
            pendingUntil = 0;
            alignWheel = false;
            wheelWasVisible = false;
            stockLabels.restore();
            clearPreviewCrop();
        }

        private void clearPreviewCrop() {
            visibleCropFactor = 1f;
            if (surfaceZoom16 != null) {
                try { surfaceZoom16.restore(); }
                catch (ReflectiveOperationException | RuntimeException error) { log(error); }
            }
            if (previewCrop != null) {
                try { previewCrop.restore(); }
                catch (Throwable error) { log(error); }
                previewCrop = null;
            }
        }

        private void hide() {
            hideZoom();
            toolbar.hide();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        private void hideZoom() {
            hideLensEffects();
            disableDigitalZoom();
        }

        void stop() {
            handler.removeCallbacks(this);
            hide();
            toolbar.remove();
            if (processing != null) {
                processing.remove();
                processing = null;
            }
        }

        private void disableDigitalZoom() {
            resetCrop();
            removeWheel();
        }

        /** True while the wheel is paused after an error, or for good after repeated errors. */
        private boolean wheelFaulted() {
            return wheelFaults >= MAX_WHEEL_FAULTS || SystemClock.elapsedRealtime() < wheelRetryAt;
        }

        private void recoverWheel(Throwable error) {
            long now = SystemClock.elapsedRealtime();
            if (now < wheelRetryAt) return;
            if (now - lastWheelFault > WHEEL_FAULT_RESET_MS) wheelFaults = 0;
            wheelFaults++;
            lastWheelFault = now;
            wheelRetryAt = now + WHEEL_RETRY_MS;
            log(error);
            if (wheelFaults >= MAX_WHEEL_FAULTS) {
                XposedBridge.log("AlmaZoom: zoom wheel disabled until the camera is reopened");
            }
            try {
                resetCrop();
            } catch (RuntimeException | LinkageError resetError) {
                log(resetError);
            } finally {
                clearPreviewCrop();
                try {
                    removeWheel();
                } catch (RuntimeException | LinkageError removeError) {
                    log(removeError);
                }
            }
        }

        private void removeWheel() {
            if (wheel == null) return;
            ZoomWheel installed = wheel;
            try {
                installed.remove();
            } finally {
                wheel = null;
            }
        }
    }

    /** Short form for small buttons: from 100x up the fraction is dropped (538 instead of 538,3). */
    private static String formatCompact(int zoom) {
        return zoom >= 100 * UNIT ? Integer.toString(Math.round(zoom / (float) UNIT)) : format(zoom);
    }

    private static String format(int zoom) {
        return String.format(Locale.US, "%.3f", (double) zoom / UNIT)
                .replaceFirst("\\.?0+$", "").replace('.', ',');
    }

    private static final class PreviewCrop {
        final View view;
        final Matrix base = new Matrix();
        final Matrix requestedTransform = new Matrix();
        final Matrix observedTransform = new Matrix();
        final Rect originalClip;
        final float scaleX, scaleY, pivotX, pivotY;
        final Method clipping;
        final boolean originallyClipped;
        float lastFactor;
        int lastWidth, lastHeight;

        PreviewCrop(View view) throws Exception {
            this.view = view;
            originalClip = view.getClipBounds();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            if (view instanceof TextureView texture) {
                texture.getTransform(base);
                clipping = null;
                originallyClipped = false;
            } else {
                clipping = findMethodBestMatch(SurfaceView.class, "setEnableSurfaceClipping", boolean.class);
                originallyClipped = getBooleanField(view, "mClipSurfaceToBounds");
                clipping.invoke(view, true);
            }
        }

        static boolean supported(View view) {
            if (view instanceof TextureView) return true;
            if (!(view instanceof SurfaceView)) return false;
            try {
                findMethodBestMatch(SurfaceView.class, "setEnableSurfaceClipping", boolean.class);
                getBooleanField(view, "mClipSurfaceToBounds");
                return true;
            } catch (LinkageError | RuntimeException unavailable) {
                return false;
            }
        }

        boolean apply(float factor) {
            int w = view.getWidth();
            int h = view.getHeight();
            boolean sameCrop = lastFactor == factor && lastWidth == w && lastHeight == h;
            if (view instanceof TextureView texture) {
                texture.getTransform(observedTransform);
                boolean current = lastFactor > 1f && observedTransform.equals(requestedTransform);
                if (sameCrop && current) return true;
                if (!current) base.set(observedTransform);
                requestedTransform.set(base);
                requestedTransform.postScale(factor, factor, w / 2f, h / 2f);
                texture.setTransform(requestedTransform);
            } else {
                applySurface(factor, w, h, sameCrop);
            }
            lastFactor = factor;
            lastWidth = w;
            lastHeight = h;
            return true;
        }

        private void applySurface(float factor, int w, int h, boolean sameCrop) {
            int cropW = ZoomMath.cropSize(w, factor);
            int cropH = ZoomMath.cropSize(h, factor);
            int left = (w - cropW) / 2;
            int top = (h - cropH) / 2;
            Rect clip = new Rect(left, top, left + cropW, top + cropH);
            if (sameCrop && view.getScaleX() == scaleX * factor && view.getScaleY() == scaleY * factor
                    && clip.equals(view.getClipBounds())) return;
            view.setClipBounds(clip);
            view.setPivotX(w / 2f);
            view.setPivotY(h / 2f);
            view.setScaleX(scaleX * factor);
            view.setScaleY(scaleY * factor);
        }

        void restore() throws Exception {
            if (view instanceof TextureView texture) {
                texture.getTransform(observedTransform);
                if (observedTransform.equals(requestedTransform)) texture.setTransform(base);
            }
            else {
                view.setScaleX(scaleX);
                view.setScaleY(scaleY);
                view.setPivotX(pivotX);
                view.setPivotY(pivotY);
                view.setClipBounds(originalClip);
                clipping.invoke(view, originallyClipped);
            }
        }
    }
}
