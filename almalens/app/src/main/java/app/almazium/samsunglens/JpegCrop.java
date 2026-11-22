package app.almazium.samsunglens;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class JpegCrop {
    /** Output is never smaller than this on the long edge, so heavy zoom is not saved as a thumbnail. */
    private static final int MIN_OUTPUT_EDGE = 2048;
    /** Upper bound on the long edge, keeps memory use sane inside the camera process. */
    private static final int MAX_OUTPUT_EDGE = 4096;
    private static final long MAX_DECODE_PIXELS = 16_000_000L;
    private static final long STALE_TEMP_MS = 10 * 60 * 1000L;
    private static final int MAX_INPUT_BYTES = 45 * 1024 * 1024;
    private static final String[] EXIF_TAGS = {
            "Orientation", "Make", "Model", "DateTime", "DateTimeOriginal",
            "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "ExposureTime",
            "FNumber", "PhotographicSensitivity", "FocalLength", "FocalLengthIn35mmFilm",
            "WhiteBalance", "Flash", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude",
            "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSDateStamp", "GPSTimeStamp",
            "ExposureBiasValue", "ExposureProgram", "ExposureMode", "MeteringMode", "LightSource",
            "ApertureValue", "MaxApertureValue", "ShutterSpeedValue", "BrightnessValue", "SubjectDistance",
            "SceneCaptureType", "ColorSpace", "Software",
            "LensMake", "LensModel", "LensSpecification"
    };

    private JpegCrop() {}

    static boolean isHighResolution(int width, int height) {
        return Math.max(width, height) >= 6500 || (long) width * height >= 30_000_000L;
    }

    record Result(int inputWidth, int inputHeight, int width, int height, long inputBytes) {}

    static Result replaceProcessed(File source, float factor) throws IOException {
        long bytes = source.length();
        if (!Float.isFinite(factor) || factor <= 1f || !source.isFile()
                || bytes < 4 || bytes > MAX_INPUT_BYTES) return null;
        removeStaleTemps(source.getParentFile());
        File result = File.createTempFile(".almazoom-", ".jpg", source.getParentFile());
        try {
            Result crop = writeCrop(source, factor, result, bytes);
            if (crop != null) {
                Files.move(result.toPath(), source.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return crop;
        } finally {
            Files.deleteIfExists(result.toPath());
        }
    }

    /** A crash between createTempFile and the final move would leave a hidden temp file behind. */
    private static void removeStaleTemps(File directory) {
        File[] stale = directory == null ? null : directory.listFiles(
                (dir, name) -> name.startsWith(".almazoom-") && name.endsWith(".jpg"));
        if (stale == null) return;
        long limit = System.currentTimeMillis() - STALE_TEMP_MS;
        for (File file : stale) {
            if (file.lastModified() < limit) file.delete();
        }
    }

    @SuppressWarnings("deprecation")
    private static Result writeCrop(File source, float factor, File result, long bytes) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            if (input.read() != 0xff || input.read() != 0xd8) return null;
        }
        ExifInterface original = new ExifInterface(source.getAbsolutePath());
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(source.getAbsolutePath(), false);
        return writeCrop(decoder, original, factor, result, bytes);
    }

    private static Result writeCrop(BitmapRegionDecoder decoder, ExifInterface original,
                                    float factor, File result, long bytes) throws IOException {
        if (decoder == null) return null;
        Bitmap crop;
        int width, height, outW, outH;
        try {
            width = decoder.getWidth();
            height = decoder.getHeight();
            if (isHighResolution(width, height)) return null;
            int w = ZoomMath.cropSize(width, factor);
            int h = ZoomMath.cropSize(height, factor);
            int x = (width - w) / 2;
            int y = (height - h) / 2;
            // Keep the real detail of the cropped area: at least MIN_OUTPUT_EDGE, at most the source size
            // and MAX_OUTPUT_EDGE.
            int fullEdge = Math.max(width, height);
            int targetEdge = Math.min(Math.min(fullEdge, MAX_OUTPUT_EDGE),
                    Math.max(MIN_OUTPUT_EDGE, Math.max(w, h)));
            float resize = (float) targetEdge / fullEdge;
            outW = Math.max(1, Math.round(width * resize));
            outH = Math.max(1, Math.round(height * resize));
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(w, h) / options.inSampleSize > 2 * targetEdge
                    || (long) (w / options.inSampleSize) * (h / options.inSampleSize) > MAX_DECODE_PIXELS) {
                options.inSampleSize *= 2;
            }
            crop = decoder.decodeRegion(new Rect(x, y, x + w, y + h), options);
        } finally {
            decoder.recycle();
        }
        if (crop == null) return null;

        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(crop, outW, outH, true);
            try (FileOutputStream output = new FileOutputStream(result)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 97, output)) return null;
            }
            ExifInterface outputExif = new ExifInterface(result.getAbsolutePath());
            for (String tag : EXIF_TAGS) {
                String value = original.getAttribute(tag);
                if (value != null) outputExif.setAttribute(tag, value);
            }
            applyZoomExif(original, outputExif, factor);
            outputExif.setAttribute("ImageWidth", Integer.toString(outW));
            outputExif.setAttribute("ImageLength", Integer.toString(outH));
            outputExif.setAttribute("PixelXDimension", Integer.toString(outW));
            outputExif.setAttribute("PixelYDimension", Integer.toString(outH));
            outputExif.saveAttributes();
            return new Result(width, height, outW, outH, bytes);
        } finally {
            if (scaled != null && scaled != crop) scaled.recycle();
            crop.recycle();
        }
    }

    /** The crop changes the 35mm-equivalent focal length and adds to the digital zoom ratio. */
    private static void applyZoomExif(ExifInterface original, ExifInterface output, float factor) {
        String equivalent = original.getAttribute("FocalLengthIn35mmFilm");
        if (equivalent != null) {
            try {
                int value = Integer.parseInt(equivalent.trim());
                output.setAttribute("FocalLengthIn35mmFilm", Integer.toString(Math.round(value * factor)));
            } catch (NumberFormatException ignored) {
                // leave the copied value as is
            }
        }
        double ratio = parseRational(original.getAttribute("DigitalZoomRatio"), 1.0) * factor;
        output.setAttribute("DigitalZoomRatio", Math.round(ratio * 1000.0) + "/1000");
    }

    private static double parseRational(String value, double fallback) {
        if (value == null) return fallback;
        try {
            String[] parts = value.trim().split("/");
            double number = Double.parseDouble(parts[0]);
            double divisor = parts.length > 1 ? Double.parseDouble(parts[1]) : 1.0;
            return divisor == 0.0 || number <= 0.0 ? fallback : number / divisor;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
