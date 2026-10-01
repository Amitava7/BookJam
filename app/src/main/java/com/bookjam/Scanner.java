package com.bookjam;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Reads folders through the storage access framework: no storage permission,
 * just the folders you picked.
 *
 * A folder with audio files in it is a book named after the folder, and its
 * files play in natural order. Pick a folder of books instead and each
 * sub-folder holding audio becomes a book. Sub-folders called "CD 1", "Disc 2"
 * or "Part 3" are taken as one book split up, and play one after the other.
 */
final class Scanner {

    /** Folder scans, durations and covers, one at a time, off the main thread. */
    static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();

    private static final String[] COLS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};

    private static final Pattern PART = Pattern.compile(
            "(?i)\\s*(cd|disc|disk|part|pt|vol|volume)[\\s._-]*\\d+\\s*");

    private static final String[] AUDIO = {
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka",
            "amr", "awb", "3gp", "aif", "aiff", "mp2"};
    private static final String[] IMAGE = {"jpg", "jpeg", "png", "webp", "bmp"};

    private Scanner() {
    }

    static final class Entry {
        String doc, name, mime;
        boolean dir;
    }

    static final class Found {
        final String doc, name;

        Found(String doc, String name) {
            this.doc = doc;
            this.name = name;
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean in(String[] list, String s) {
        for (String x : list) if (x.equals(s)) return true;
        return false;
    }

    static boolean isAudio(Entry e) {
        if (e.dir) return false;
        return in(AUDIO, ext(e.name)) || (e.mime != null && e.mime.startsWith("audio/")
                && !e.mime.equals("audio/x-mpegurl") && !e.mime.equals("audio/mpegurl"));
    }

    static boolean isImage(Entry e) {
        return !e.dir && (in(IMAGE, ext(e.name)) || (e.mime != null && e.mime.startsWith("image/")));
    }

    /** One folder's contents, or null if it cannot be read (moved, deleted, access gone). */
    static List<Entry> list(ContentResolver cr, Uri tree, String doc) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (Cursor c = cr.query(kids, COLS, null, null, null)) {
            if (c == null) return null;
            List<Entry> out = new ArrayList<>();
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.doc = c.getString(0);
                e.name = c.getString(1);
                e.mime = c.getString(2);
                // Dot files include the "._01.mp3" shadows macOS leaves on
                // copied folders, which look like audio but are not.
                if (e.doc == null || e.name == null || e.name.startsWith(".")) continue;
                e.dir = Document.MIME_TYPE_DIR.equals(e.mime);
                out.add(e);
            }
            return out;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** A folder's display name. */
    static String name(ContentResolver cr, Uri tree, String doc) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
        try (Cursor c = cr.query(u, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through to the document id
        }
        String s = doc;
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return cut >= 0 && cut < s.length() - 1 ? s.substring(cut + 1) : s;
    }

    /** The books in a folder: itself if it holds audio, otherwise its sub-folders. */
    static void findBooks(ContentResolver cr, Uri tree, String doc, String name, int depth,
                          List<Found> out) {
        List<Entry> kids = list(cr, tree, doc);
        if (kids == null) return;
        List<Entry> dirs = new ArrayList<>();
        for (Entry e : kids) {
            if (isAudio(e)) {
                out.add(new Found(doc, name));
                return;
            }
            if (e.dir) dirs.add(e);
        }
        if (depth >= 4) return;
        List<Found> inner = new ArrayList<>();
        for (Entry d : dirs) findBooks(cr, tree, d.doc, d.name, depth + 1, inner);
        if (inner.isEmpty()) return;
        boolean parts = true;
        for (Found f : inner) {
            boolean child = false;
            for (Entry d : dirs) if (d.doc.equals(f.doc)) child = true;
            if (!child || !PART.matcher(f.name).matches()) parts = false;
        }
        if (parts) out.add(new Found(doc, name));
        else out.addAll(inner);
    }

    /** Every audio file under a book's folder in natural order, or null if unreadable. */
    static List<Library.Track> tracks(ContentResolver cr, Uri tree, String doc) {
        List<Library.Track> out = new ArrayList<>();
        if (!collect(cr, tree, doc, "", 0, out)) return null;
        Collections.sort(out, (a, b) -> Natural.ORDER.compare(a.name, b.name));
        for (int i = 0; i < out.size(); i++) out.get(i).ord = i;
        return out;
    }

    private static boolean collect(ContentResolver cr, Uri tree, String doc, String prefix,
                                   int depth, List<Library.Track> out) {
        List<Entry> kids = list(cr, tree, doc);
        if (kids == null) return false;
        for (Entry e : kids) {
            if (e.dir) {
                if (depth < 3) collect(cr, tree, e.doc, prefix + e.name + "/", depth + 1, out);
            } else if (isAudio(e)) {
                Library.Track t = new Library.Track();
                t.doc = e.doc;
                t.name = prefix + Fmt.stripExtension(e.name);
                out.add(t);
            }
        }
        return true;
    }

    /** The picture in a book's folder most likely to be its cover. */
    static Entry coverImage(ContentResolver cr, Uri tree, String doc) {
        List<Entry> kids = list(cr, tree, doc);
        if (kids == null) return null;
        Entry best = null;
        int bestScore = -1;
        for (Entry e : kids) {
            if (!isImage(e)) continue;
            String n = Fmt.stripExtension(e.name).toLowerCase(Locale.ROOT);
            int score = n.equals("cover") || n.equals("folder") ? 3
                    : n.contains("cover") || n.contains("front") || n.startsWith("album") ? 2
                    : 1;
            if (score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    // ---- the jobs, all run on BACKGROUND ------------------------------------

    /**
     * Re-reads a book's folder into the library and makes its cover if it has
     * none yet. False if the folder could not be read.
     */
    static boolean refreshBook(Context c, Library.Book b) {
        if (b == null) return false;
        List<Library.Track> found = tracks(c.getContentResolver(), b.treeUri(), b.doc);
        if (found == null) return false;
        Library.get(c).setTracks(b.id, found);
        if (!Covers.file(c, b.id).exists()) Covers.build(c, b.id, b.treeUri(), b.doc, found);
        return true;
    }

    /** Adds the books in a folder you just picked. Returns their ids. */
    static List<Long> addPicked(Context c, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        Library lib = Library.get(c);
        lib.addRoot(tree);
        String root = DocumentsContract.getTreeDocumentId(tree);
        List<Found> found = new ArrayList<>();
        findBooks(cr, tree, root, name(cr, tree, root), 0, found);
        List<Long> ids = new ArrayList<>();
        for (Found f : found) {
            long id = lib.addBook(tree, f.doc, f.name);
            if (refreshBook(c, lib.book(id))) ids.add(id);
        }
        return ids;
    }

    /** Looks through the folders picked before for books added since. Returns their ids. */
    static List<Long> rescanRoots(Context c) {
        ContentResolver cr = c.getContentResolver();
        Library lib = Library.get(c);
        List<Long> ids = new ArrayList<>();
        List<UriPermission> granted = cr.getPersistedUriPermissions();
        for (Uri tree : lib.roots()) {
            boolean ok = false;
            for (UriPermission p : granted) if (p.getUri().equals(tree) && p.isReadPermission()) ok = true;
            if (!ok) continue;
            String root = DocumentsContract.getTreeDocumentId(tree);
            List<Found> found = new ArrayList<>();
            findBooks(cr, tree, root, name(cr, tree, root), 0, found);
            for (Found f : found) {
                if (lib.known(tree, f.doc)) continue;
                long id = lib.addBook(tree, f.doc, f.name);
                if (refreshBook(c, lib.book(id))) ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Fills in the length of every file that does not have one yet, which is
     * what lets the library show time left. Returns the new lengths by file.
     */
    static Map<String, Long> measure(Context c, long bookId) {
        Library lib = Library.get(c);
        Map<String, Long> out = new HashMap<>();
        Library.Book b = lib.book(bookId);
        if (b == null) return out;
        Uri tree = b.treeUri();
        for (Library.Track t : lib.tracks(bookId)) {
            if (t.dur > 0) continue;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(c, t.uri(tree));
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    long ms = Long.parseLong(d.trim());
                    if (ms > 0) {
                        out.put(t.doc, ms);
                        lib.saveDuration(bookId, t.doc, ms);
                    }
                }
            } catch (Exception ignored) {
                // unreadable or not really audio; it simply has no length
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
        return out;
    }
}
