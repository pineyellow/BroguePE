package com.pineyellow.broguepe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Saves a Recent Games recording to a file, and plays a replay file. The
 *  player picks the file in Android's own file picker, which lets the app use
 *  only that one file, so the app needs no storage permission. An exported
 *  replay is the recording just as the engine wrote it, which Brogue CE can
 *  play too, except that an Easy run's is marked as PE's only (see
 *  RecentGames.tagPeOnly). */
final class ReplayFiles {

    static final int REQUEST_EXPORT = 7301;
    static final int REQUEST_IMPORT = 7302;

    private static final String TAG = "BrogueReplayFiles";
    // A type with no extension of its own, so the picker keeps ".broguerec".
    private static final String EXPORT_TYPE = "application/octet-stream";
    // The engine reads a replay from its file while it plays, so the copy is
    // kept out of the cache, which Android may clear at any time.
    private static final String IMPORTED_NAME = "imported" + RecentGames.RECORDING_SUFFIX;
    // Far beyond any real recording; stops a wrong pick from filling storage.
    private static final long MAX_IMPORT_BYTES = 64L * 1024 * 1024;

    private final BrogueActivity activity;
    // From opening the picker until its result. UI thread only.
    private boolean pickerOpen;
    private File pendingExport;
    private int[] pendingImportMode;

    ReplayFiles(BrogueActivity activity) {
        this.activity = activity;
    }

    /** Asks where to save the game's recording, then copies it there. */
    void export(JSONObject game, File recording) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(EXPORT_TYPE);
        // Only its first bytes, like nativeRecordingIsPlayable's check.
        byte[] header = new byte[RecentGames.RECORDING_HEADER_LENGTH];
        int length = 0;
        try (InputStream in = new FileInputStream(recording)) {
            length = readHeader(in, header);
        } catch (IOException e) {
            Log.w(TAG, "couldn't read the header of " + recording, e);
        }
        intent.putExtra(Intent.EXTRA_TITLE, RecentGames.exportName(game, header, length));
        if (launch(intent, REQUEST_EXPORT)) pendingExport = recording;
    }

    /** Asks for a replay file and plays it. Afterwards Personal Stats reopens
     *  on the given mode. */
    void startImport(int variant, int difficulty) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Android has no type for recordings, so every file is offered and
        // the picked one's header is checked.
        intent.setType("*/*");
        if (launch(intent, REQUEST_IMPORT)) pendingImportMode = new int[] { variant, difficulty };
    }

    private boolean launch(Intent intent, int requestCode) {
        // A second tap before the picker covers the screen.
        if (pickerOpen) return false;
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            toast("No file picker on this device");
            return false;
        }
        pickerOpen = true;
        return true;
    }

    /** The picker's answer. Returns false if the request wasn't from here. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return false;
        File recording = pendingExport;
        int[] mode = pendingImportMode;
        pickerOpen = false;
        pendingExport = null;
        pendingImportMode = null;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true;
        // Both are lost if Android closed the app while the picker was open.
        if (requestCode == REQUEST_EXPORT) {
            if (recording == null) toast("Couldn't export the replay. Try again.");
            else saveTo(recording, uri);
        } else {
            if (mode == null) toast("Couldn't open the replay. Try again.");
            else importAndWatch(uri, mode[0], mode[1]);
        }
        return true;
    }

    private void saveTo(File recording, Uri target) {
        new Thread(() -> {
            boolean saved = false;
            boolean peOnly = false;
            try (InputStream in = new FileInputStream(recording);
                 OutputStream out = activity.getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("no stream for " + target);
                byte[] header = new byte[RecentGames.RECORDING_HEADER_LENGTH];
                int length = readHeader(in, header);
                peOnly = RecentGames.tagPeOnly(header, length);
                out.write(header, 0, length);
                copy(in, out, Long.MAX_VALUE);
                saved = true;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "export failed", e);
            }
            final boolean ok = saved;
            final boolean onlyHere = peOnly;
            activity.runOnUiThread(() -> {
                if (!ok) {
                    toast("Couldn't export the replay");
                } else if (onlyHere) {
                    // Before it's shared with someone playing Brogue CE.
                    Toast.makeText(activity, "Replay exported. Easy mode replays only play in Brogue PE",
                        Toast.LENGTH_LONG).show();
                } else {
                    toast("Replay exported");
                }
            });
        }, "BrogueReplayExport").start();
    }

    private void importAndWatch(Uri source, int variant, int difficulty) {
        File target = new File(activity.getNoBackupFilesDir(), IMPORTED_NAME);
        new Thread(() -> {
            String error = copyIn(source, target);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (error != null) {
                    toast(error);
                } else {
                    activity.watchImportedRecording(target, variant, difficulty);
                }
            });
        }, "BrogueReplayImport").start();
    }

    /** Copies the picked file to target and checks that this version can play
     *  it. Returns what to tell the player if not. */
    private String copyIn(Uri source, File target) {
        File partial = new File(target.getPath() + ".tmp");
        try (InputStream in = activity.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("no stream for " + source);
            if (!copy(in, out, MAX_IMPORT_BYTES)) {
                partial.delete();
                return "That file isn't a Brogue replay";
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "import failed", e);
            partial.delete();
            return "Couldn't read that file";
        }
        byte[] header = new byte[RecentGames.RECORDING_HEADER_LENGTH];
        int length;
        try (InputStream in = new FileInputStream(partial)) {
            length = readHeader(in, header);
        } catch (IOException e) {
            partial.delete();
            return "Couldn't read that file";
        }
        // An Easy replay exported from here gets its own version back.
        if (RecentGames.untagPeOnly(header, length)) {
            try (RandomAccessFile file = new RandomAccessFile(partial, "rw")) {
                file.write(header, 0, length);
            } catch (IOException e) {
                partial.delete();
                return "Couldn't read that file";
            }
        }
        String version = RecentGames.recordingVersion(header, length);
        if (version == null) {
            partial.delete();
            return "That file isn't a Brogue replay";
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            return "Couldn't read that file";
        }
        if (!activity.nativeRecordingIsPlayable(target.getAbsolutePath())) {
            return "This replay is from another version of Brogue (" + version + ")";
        }
        return null;
    }

    /** Reads up to header.length bytes from the start of a file. Returns how
     *  many it got, fewer only if the file is shorter. */
    private static int readHeader(InputStream in, byte[] header) throws IOException {
        int length = 0;
        int n;
        while (length < header.length
                && (n = in.read(header, length, header.length - length)) > 0) {
            length += n;
        }
        return length;
    }

    /** Copies at most limit bytes. Returns false if there was more. */
    private static boolean copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > limit) return false;
            out.write(buffer, 0, n);
        }
        return true;
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
