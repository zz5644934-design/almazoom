package app.almazium.samsunglens;

import android.os.Environment;

import java.io.File;
import java.io.IOException;

/**
 * Tells the gallery that a photo is still being processed. The camera creates an empty hidden file named like the
 * photo in DCIM/.almalens when the shot starts and deletes it when the final photo is published. The gallery
 * (another process) only has to look whether that file exists. Samsung's own flag cannot be used: it lives inside
 * the JPEG, and cropping the photo re-encodes it.
 */
final class ProcessingMarker {
    private static final long STALE_MS = 60_000;

    private ProcessingMarker() {}

    private static File dir() {
        return new File(Environment.getExternalStorageDirectory(), "DCIM/.almalens");
    }

    static void set(File photo) {
        if (photo == null) return;
        try {
            File dir = dir();
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            sweep(dir);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, photo.getName()).createNewFile();
        } catch (IOException | SecurityException ignored) {
            // without the marker only the spoiler in the gallery is missing
        }
    }

    static void clear(File photo) {
        if (photo == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(), photo.getName()).delete();
        } catch (SecurityException ignored) {
        }
    }

    static boolean isSet(String name) {
        if (name == null || name.isEmpty()) return false;
        try {
            File marker = new File(dir(), name);
            return marker.exists() && System.currentTimeMillis() - marker.lastModified() < STALE_MS;
        } catch (SecurityException error) {
            return false;
        }
    }

    private static void sweep(File dir) {
        File[] old = dir.listFiles();
        if (old == null) return;
        long limit = System.currentTimeMillis() - STALE_MS;
        for (File file : old) {
            if (file.lastModified() < limit) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }
}
