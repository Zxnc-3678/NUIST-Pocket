package cn.nuist.pocket;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.os.Bundle;
import cn.nuist.pocket.data.WallpaperStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Separate temporary storage; Java avoids relying on Kotlin helpers removed from the release app. */
public final class AppearanceStorageInstrumentation {
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static void importImage(WallpaperStore store, InputStream input) throws Exception {
        try { WallpaperStore.class.getMethod("import", InputStream.class).invoke(store, input); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }
    private static byte[] fingerprint(File file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
    }
    private static void requireNoTemporaryInputs(File folder) {
        File[] remaining = folder.listFiles((dir, name) -> name.startsWith("wallpaper-import-"));
        require(remaining != null && remaining.length == 0, "temporary inputs removed");
    }
    public static void run(Instrumentation instrumentation) {
        File folder = new File(instrumentation.getTargetContext().getCacheDir(), "wallpaper-check-" + System.nanoTime());
        folder.mkdirs();
        ContextWrapper isolated = new ContextWrapper(instrumentation.getTargetContext()) {
            @Override public File getFilesDir() { return folder; }
            @Override public File getCacheDir() { return folder; }
        };
        WallpaperStore store = new WallpaperStore(isolated);
        File fixture = new File(folder, "rotated-fixture.jpg");
        Bundle result = new Bundle();
        boolean success = false;
        try {
            require(store.load() == null, "empty load");
            Bitmap input = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888);
            input.eraseColor(Color.rgb(31, 117, 98));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            require(input.compress(Bitmap.CompressFormat.PNG, 100, bytes), "fixture png");
            try (FileOutputStream output = new FileOutputStream(fixture)) {
                require(input.compress(Bitmap.CompressFormat.JPEG, 90, output), "fixture jpeg");
            }
            input.recycle();
            importImage(store, new ByteArrayInputStream(bytes.toByteArray()));
            require(store.exists(), "stored image");
            Bitmap decoded = store.load();
            require(decoded != null && decoded.getWidth() == 1600 && decoded.getHeight() == 800, "resize");
            decoded.recycle();
            File stored = new File(folder, "timetable-wallpaper.webp");
            byte[] original = fingerprint(stored);
            boolean rejected = false;
            try { importImage(store, new ByteArrayInputStream(new byte[]{1, 2, 3})); }
            catch (Exception expected) { rejected = true; }
            require(rejected && Arrays.equals(original, fingerprint(stored)), "malformed preserves previous");
            final boolean[] closed = {false};
            InputStream oversized = new InputStream() {
                int remaining = 16 * 1024 * 1024 + 1;
                @Override public int read() { return remaining-- > 0 ? 0 : -1; }
                @Override public int read(byte[] b, int offset, int length) {
                    if (remaining <= 0) return -1;
                    int count = Math.min(remaining, length);
                    Arrays.fill(b, offset, offset + count, (byte) 0);
                    remaining -= count; return count;
                }
                @Override public void close() { closed[0] = true; }
            };
            rejected = false;
            try { importImage(store, oversized); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && closed[0] && Arrays.equals(original, fingerprint(stored)), "oversized preserves previous and closes input");
            requireNoTemporaryInputs(folder);
            final boolean[] failedInputClosed = {false};
            InputStream unreadable = new InputStream() {
                boolean first = true;
                @Override public int read() throws IOException { throw new IOException("simulated input failure"); }
                @Override public int read(byte[] b, int offset, int length) throws IOException {
                    if (!first) throw new IOException("simulated input failure");
                    first = false; b[offset] = 1; return 1;
                }
                @Override public void close() { failedInputClosed[0] = true; }
            };
            rejected = false;
            try { importImage(store, unreadable); }
            catch (IOException expected) { rejected = true; }
            require(rejected && failedInputClosed[0] && Arrays.equals(original, fingerprint(stored)), "read failure preserves previous and closes input");
            requireNoTemporaryInputs(folder);
            ExifInterface exif = new ExifInterface(fixture.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
            importImage(store, new FileInputStream(fixture));
            Bitmap rotated = store.load();
            require(rotated != null && rotated.getWidth() == 800 && rotated.getHeight() == 1600, "photo orientation");
            rotated.recycle();
            store.clear();
            require(!store.exists() && store.load() == null, "remove");
            requireNoTemporaryInputs(folder);
            result.putString("stream", "WALLPAPER_CHECKS passed: missing, resize, malformed_preserves_previous, oversized_preserves_previous, stream_closed, photo_orientation, remove, temporary_input_removed, read_failure_cleanup\n");
            success = true;
        } catch (Throwable error) {
            result.putString("stream", "WALLPAPER_CHECKS failed: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
        } finally {
            fixture.delete(); store.clear(); folder.delete();
        }
        instrumentation.finish(success ? -1 : 0, result);
    }
}
