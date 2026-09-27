package com.pineyellow.broguepe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reproducible source-resolution masks in private, non-backed-up storage.
 * Disk failures only lose caching; writes never run on the caller's thread. */
final class TileMaskFiles {
    // Bump when trimming, padding, or intensity rules change.
    private static final int FORMAT_VERSION = 1;
    private static File directory;
    // At most 17 source masks retained (16 waiting plus one being written).
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
        runnable -> new Thread(runnable, "TileMaskWriter"),
        new ThreadPoolExecutor.AbortPolicy());
    // Protected by this class's monitor. Readers must not open an AtomicFile
    // mid-write: openRead can recover/delete temporary files on some versions.
    private static final Set<String> PENDING = new HashSet<>();

    private TileMaskFiles() { }

    static synchronized Bitmap load(BrogueActivity activity, int index, boolean trimmed) {
        try {
            AtomicFile file = file(activity, index, trimmed);
            if (PENDING.contains(file.getBaseFile().getPath())) return null;
            try (InputStream input = file.openRead()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Takes ownership only when recycleAfterSave is true, including on rejection.
     * Shared display bitmaps remain read-only and are left for normal GC. */
    static synchronized void saveAsync(BrogueActivity activity, int index, boolean trimmed,
                                       Bitmap mask, boolean recycleAfterSave) {
        AtomicFile file;
        try {
            file = file(activity, index, trimmed);
        } catch (IOException ignored) {
            if (recycleAfterSave) mask.recycle();
            return;
        }
        String path = file.getBaseFile().getPath();
        if (!PENDING.add(path)) {
            if (recycleAfterSave) mask.recycle();
            return;
        }
        try {
            WRITER.execute(() -> {
                try {
                    save(file, mask);
                } finally {
                    if (recycleAfterSave) mask.recycle();
                    synchronized (TileMaskFiles.class) {
                        PENDING.remove(path);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A busy cache can skip persistence; never stall the UI to catch up.
            PENDING.remove(path);
            if (recycleAfterSave) mask.recycle();
        }
    }

    private static void save(AtomicFile file, Bitmap mask) {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            if (!mask.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Cannot encode tile mask");
            }
            file.finishWrite(output);
        } catch (IOException ignored) {
            if (output != null) file.failWrite(output);
        }
    }

    private static AtomicFile file(BrogueActivity activity, int index, boolean trimmed)
            throws IOException {
        if (directory == null) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            try (InputStream input = activity.getAssets().open("tiles.png")) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value >>> 4) & 15, 16));
                hash.append(Character.forDigit(value & 15, 16));
            }
            directory = new File(activity.getNoBackupFilesDir(),
                "tile-masks-v" + FORMAT_VERSION + "-" + hash);
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create mask directory");
        }
        return new AtomicFile(new File(directory,
            index + (trimmed ? "_trimmed.png" : "_full.png")));
    }
}
