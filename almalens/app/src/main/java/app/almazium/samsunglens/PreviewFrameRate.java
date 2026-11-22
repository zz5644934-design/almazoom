package app.almazium.samsunglens;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Range;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findMethodBestMatch;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

final class PreviewFrameRate {
    private static final String CORE = "com.samsung.android.camera.core2.";
    private static final CaptureRequest.Key<Range<Integer>> FPS = CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE;
    private final ThreadLocal<Flight> active = new ThreadLocal<>();
    private volatile boolean reportedChoices;
    private final Set<Method> hooked = new HashSet<>();
    private final Map<CameraCaptureSession, Integer> ceilings = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<CameraCaptureSession, Range<Integer>> logged = Collections.synchronizedMap(new WeakHashMap<>());

    private record Flight(CameraCaptureSession session, List<Range<Integer>> choices,
                          Map<CaptureRequest, CaptureRequest[]> requests) {}

    void install(ClassLoader loader, BooleanSupplier enabled) {
        Class<?> device = findClass(CORE + "device.CamDeviceImpl", loader);
        Class<?> counts = findClass(CORE + "CamDeviceRepeatingRequestCnt", loader);
        Class<?> callback = findClass(CORE + "CamDevice$PreviewStateCallback", loader);
        findAndHookMethod(CaptureRequest.Builder.class, "build", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Flight flight = active.get();
                if (flight == null || p.hasThrowable()) return;
                recordChoices(p, flight);
            }
        });
        findAndHookMethod(device, "y0", counts, callback, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("almaFpsPrevious", active.get());
                active.remove();
                if (Boolean.TRUE.equals(p.args[2])) return;
                try {
                    if (!enabled.getAsBoolean()) return;
                    Object source = p.thisObject;
                    if (!"DEFAULT".equals(enumName(getObjectField(source, "J")))) return;
                    Object session = getObjectField(source, "K");
                    Object capability = getObjectField(source, "k");
                    if (!(session instanceof CameraCaptureSession cameraSession) || capability == null) return;
                    int ceiling = ceilings.getOrDefault(cameraSession, 120);
                    if (ceiling < 30) return;
                    CameraCharacteristics traits = (CameraCharacteristics) callMethod(
                            getObjectField(capability, "d"), "a");
                    if (traits == null
                            || !Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(
                                    traits.get(CameraCharacteristics.LENS_FACING))) return;
                    List<Range<Integer>> choices = choose(
                            traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), ceiling);
                    if (choices.isEmpty()) return;
                    if (!reportedChoices) {
                        reportedChoices = true;
                        XposedBridge.log("AlmaZoom preview FPS options: " + choices);
                    }
                    hookSubmit(cameraSession.getClass());
                    active.set(new Flight(cameraSession, choices, new IdentityHashMap<>()));
                } catch (Throwable failure) {
                    XposedBridge.log("AlmaZoom preview FPS: " + failure);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                Flight previous = (Flight) p.getObjectExtra("almaFpsPrevious");
                if (previous == null) active.remove();
                else active.set(previous);
            }
        });
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> e ? e.name() : "";
    }

    static List<Range<Integer>> choose(Range<Integer>[] available, int ceiling) {
        List<Range<Integer>> choices = new ArrayList<>();
        if (available == null) return choices;
        for (int fps : new int[]{120, 60, 30}) {
            if (fps > ceiling) continue;
            // Widest range first (for example 30..60 before 60..60): a fixed 60 fps range caps the exposure
            // time at 1/60 s, which darkens the preview and the photo in dim light.
            List<Range<Integer>> sameUpper = new ArrayList<>();
            for (Range<Integer> range : available) {
                if (range.getUpper() == fps) sameUpper.add(range);
            }
            sameUpper.sort((a, b) -> Integer.compare(a.getLower(), b.getLower()));
            choices.addAll(sameUpper);
        }
        return choices;
    }

    private void recordChoices(XC_MethodHook.MethodHookParam p, Flight flight) {
        CaptureRequest original = (CaptureRequest) p.getResult();
        if (original == null) return;
        Integer intent = original.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
        if (intent == null || (intent != CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
                && intent != CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG)) return;
        Integer aeMode = original.get(CaptureRequest.CONTROL_AE_MODE);
        if (!Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON).equals(aeMode)) return;
        Range<Integer> before = original.get(FPS);
        if (before == null) return;

        CaptureRequest.Builder builder = (CaptureRequest.Builder) p.thisObject;
        CaptureRequest[] alternatives = new CaptureRequest[flight.choices().size()];
        try {
            for (int i = 0; i < alternatives.length; i++) {
                Range<Integer> choice = flight.choices().get(i);
                if (choice.equals(before)) {
                    alternatives[i] = original;
                    continue;
                }
                // A lower bound below the stock one is intended: 15..60 lets auto exposure lengthen the exposure
                // in dim light, a fixed 60..60 caps every frame and every shot at 1/60 s.
                if (choice.getUpper() < before.getUpper()) continue;
                builder.set(FPS, choice);
                alternatives[i] = (CaptureRequest) XposedBridge.invokeOriginalMethod(p.method, builder, p.args);
            }
            flight.requests().put(original, alternatives);
        } catch (Throwable failure) {
            XposedBridge.log("AlmaZoom preview FPS builder: " + failure);
        } finally {
            try {
                builder.set(FPS, before);
            } catch (RuntimeException error) {
                XposedBridge.log("AlmaZoom preview FPS restore: " + error);
            }
        }
    }

    private synchronized void hookSubmit(Class<?> impl) {
        Method method = findMethodBestMatch(impl, "setRepeatingBurst",
                List.class, CameraCaptureSession.CaptureCallback.class, Handler.class);
        if (hooked.contains(method)) return;
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Flight flight = active.get();
                if (flight == null || p.thisObject != flight.session() || !(p.args[0] instanceof List<?> stock)) return;
                submit(p, flight, stock);
            }
        });
        hooked.add(method);
    }

    private void submit(XC_MethodHook.MethodHookParam p, Flight flight, List<?> stock) throws Throwable {
        for (int i = 0; i < flight.choices().size(); i++) {
            List<CaptureRequest> changed = new ArrayList<>(stock.size());
            boolean different = false;
            for (Object request : stock) {
                CaptureRequest[] alternatives = flight.requests().get(request);
                if (alternatives == null || alternatives[i] == null) break;
                changed.add(alternatives[i]);
                different |= alternatives[i] != request;
            }
            if (!different || changed.size() != stock.size()) continue;

            Range<Integer> range = flight.choices().get(i);
            Object[] args = p.args.clone();
            args[0] = changed;
            try {
                Object result = XposedBridge.invokeOriginalMethod(p.method, p.thisObject, args);
                if (result instanceof Integer sequence && sequence < 0) {
                    ceilings.put(flight.session(), range.getUpper() - 1);
                    continue;
                }
                p.setResult(result);
                if (!range.equals(logged.put(flight.session(), range))) {
                    XposedBridge.log("AlmaZoom preview FPS requested: " + range);
                }
                return;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (!(cause instanceof CameraAccessException || cause instanceof RuntimeException)) throw e;
                ceilings.put(flight.session(), range.getUpper() - 1);
                XposedBridge.log("AlmaZoom preview FPS rejected " + range + ": " + cause);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                XposedBridge.log("AlmaZoom preview FPS fallback: " + e);
                return;
            }
        }
    }
}
