package com.bookjam;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The books you have added and where you are in each, in a small SQLite file.
 *
 * Positions are written on their own thread, every few seconds while playing
 * and on every pause, seek and file change. Each write is a committed
 * transaction, so being killed mid-chapter loses at most those few seconds.
 * Every file also keeps its own position, so going back to a chapter you
 * skipped out of picks up where you left it.
 */
final class Library extends SQLiteOpenHelper {

    /** The single writer. Saves never queue behind a folder scan. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static Library sInstance;

    static synchronized Library get(Context ctx) {
        if (sInstance == null) sInstance = new Library(ctx.getApplicationContext());
        return sInstance;
    }

    private Library(Context ctx) {
        super(ctx, "library.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Folders you picked. Looked through again on launch for new books.
        db.execSQL("CREATE TABLE roots(tree TEXT PRIMARY KEY, added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE books(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "k TEXT NOT NULL UNIQUE," +             // provider + folder document id
                "tree TEXT NOT NULL," +                 // the picked tree that grants access
                "doc TEXT NOT NULL," +                  // the book's folder
                "name TEXT NOT NULL," +
                "track TEXT," +                         // document id of the current file
                "pos INTEGER NOT NULL DEFAULT 0," +     // ms into that file
                "speed REAL NOT NULL DEFAULT 1," +
                "added INTEGER NOT NULL," +
                "played INTEGER NOT NULL DEFAULT 0," +  // last listened, wall clock
                "hidden INTEGER NOT NULL DEFAULT 0)");  // removed, but remembered
        db.execSQL("CREATE TABLE tracks(" +
                "book INTEGER NOT NULL," +
                "doc TEXT NOT NULL," +
                "name TEXT NOT NULL," +                 // path inside the book, no extension
                "ord INTEGER NOT NULL," +
                "dur INTEGER NOT NULL DEFAULT 0," +
                "pos INTEGER NOT NULL DEFAULT 0," +
                "done INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(book, doc))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
    }

    static final class Book {
        long id;
        String tree, doc, name, track;
        long pos, added, played;
        float speed;

        // Filled in by progress().
        int count, index;
        long total, heard;
        boolean timed;      // every file's length is known, so total and heard are real

        Uri treeUri() {
            return Uri.parse(tree);
        }

        boolean started() {
            return track != null && (index > 0 || pos > 0);
        }

        boolean finished() {
            return timed && total > 0 && total - heard < 2000;
        }
    }

    static final class Track {
        String doc, name;
        int ord;
        long dur, pos;
        boolean done;

        Uri uri(Uri tree) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, doc);
        }
    }

    static String key(Uri tree, String doc) {
        return tree.getAuthority() + "/" + doc;
    }

    private static final String[] BOOK = {
            "id", "tree", "doc", "name", "track", "pos", "speed", "added", "played"};

    private static Book readBook(Cursor c) {
        Book b = new Book();
        b.id = c.getLong(0);
        b.tree = c.getString(1);
        b.doc = c.getString(2);
        b.name = c.getString(3);
        b.track = c.getString(4);
        b.pos = c.getLong(5);
        b.speed = c.getFloat(6);
        b.added = c.getLong(7);
        b.played = c.getLong(8);
        return b;
    }

    private List<Book> queryBooks(String where, String[] args, String order, String limit) {
        List<Book> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("books", BOOK, where, args, null, null,
                order, limit)) {
            while (c.moveToNext()) out.add(readBook(c));
        }
        return out;
    }

    /** The library, most recently listened first, then newest added. */
    List<Book> books() {
        return queryBooks("hidden=0", null, "played DESC, added DESC, name", null);
    }

    Book book(long id) {
        List<Book> l = queryBooks("id=?", new String[]{String.valueOf(id)}, null, null);
        return l.isEmpty() ? null : l.get(0);
    }

    /** The book you listened to last, or null if you have not played anything. */
    Book last() {
        List<Book> l = queryBooks("hidden=0 AND played>0", null, "played DESC", "1");
        return l.isEmpty() ? null : l.get(0);
    }

    private Book byKey(String k) {
        List<Book> l = queryBooks("k=?", new String[]{k}, null, null);
        return l.isEmpty() ? null : l.get(0);
    }

    /** True if this folder is in the library, or was and you removed it. */
    boolean known(Uri tree, String doc) {
        return byKey(key(tree, doc)) != null;
    }

    List<Track> tracks(long book) {
        List<Track> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"doc", "name", "ord", "dur", "pos", "done"},
                "book=?", new String[]{String.valueOf(book)}, null, null, "ord")) {
            while (c.moveToNext()) {
                Track t = new Track();
                t.doc = c.getString(0);
                t.name = c.getString(1);
                t.ord = c.getInt(2);
                t.dur = c.getLong(3);
                t.pos = c.getLong(4);
                t.done = c.getInt(5) != 0;
                out.add(t);
            }
        }
        return out;
    }

    /** Works out how far through a book you are, for the library list. */
    static void progress(Book b, List<Track> tracks) {
        b.count = tracks.size();
        b.index = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).doc.equals(b.track)) b.index = i;
        }
        b.total = 0;
        b.heard = 0;
        b.timed = !tracks.isEmpty();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t.dur <= 0) b.timed = false;
            b.total += t.dur;
            if (i < b.index) b.heard += t.dur;
            else if (i == b.index) b.heard += t.dur > 0 ? Math.min(b.pos, t.dur) : b.pos;
        }
    }

    /** Adds a book, or brings back one you removed with its place intact. */
    long addBook(Uri tree, String doc, String name) {
        String k = key(tree, doc);
        Book b = byKey(k);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("tree", tree.toString());
        v.put("name", name);
        v.put("hidden", 0);
        if (b != null) {
            db.update("books", v, "id=?", new String[]{String.valueOf(b.id)});
            return b.id;
        }
        v.put("k", k);
        v.put("doc", doc);
        v.put("added", System.currentTimeMillis());
        return db.insert("books", null, v);
    }

    /**
     * Brings a book's file list in line with its folder. Files keep their
     * position and length by document id; new ones join, missing ones go.
     */
    void setTracks(long book, List<Track> found) {
        SQLiteDatabase db = getWritableDatabase();
        String id = String.valueOf(book);
        db.beginTransaction();
        try {
            Set<String> keep = new HashSet<>();
            for (Track t : found) {
                keep.add(t.doc);
                ContentValues v = new ContentValues();
                v.put("name", t.name);
                v.put("ord", t.ord);
                if (db.update("tracks", v, "book=? AND doc=?", new String[]{id, t.doc}) == 0) {
                    v.put("book", book);
                    v.put("doc", t.doc);
                    db.insert("tracks", null, v);
                }
            }
            List<String> gone = new ArrayList<>();
            try (Cursor c = db.query("tracks", new String[]{"doc"}, "book=?", new String[]{id},
                    null, null, null)) {
                while (c.moveToNext()) {
                    if (!keep.contains(c.getString(0))) gone.add(c.getString(0));
                }
            }
            for (String doc : gone) db.delete("tracks", "book=? AND doc=?", new String[]{id, doc});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void addRoot(Uri tree) {
        ContentValues v = new ContentValues();
        v.put("tree", tree.toString());
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("roots", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Folders you picked, oldest first. */
    List<Uri> roots() {
        List<Uri> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("roots", new String[]{"tree"}, null, null,
                null, null, "added")) {
            while (c.moveToNext()) out.add(Uri.parse(c.getString(0)));
        }
        return out;
    }

    // ---- writes from the player, all on the IO thread -----------------------

    /** Where you are now. listened also moves the book to the top of the library. */
    void savePosition(final long book, final String doc, final long pos, final boolean listened) {
        final long now = System.currentTimeMillis();
        IO.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            String id = String.valueOf(book);
            db.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                v.put("track", doc);
                v.put("pos", pos);
                if (listened) v.put("played", now);
                db.update("books", v, "id=?", new String[]{id});
                ContentValues t = new ContentValues();
                t.put("pos", pos);
                t.put("done", 0);
                db.update("tracks", t, "book=? AND doc=?", new String[]{id, doc});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        });
    }

    /** A file's own place, kept for when you come back to it. */
    void saveTrack(final long book, final String doc, final long pos, final boolean done) {
        IO.execute(() -> {
            ContentValues v = new ContentValues();
            v.put("pos", pos);
            v.put("done", done ? 1 : 0);
            getWritableDatabase().update("tracks", v, "book=? AND doc=?",
                    new String[]{String.valueOf(book), doc});
        });
    }

    void saveDuration(final long book, final String doc, final long dur) {
        IO.execute(() -> {
            ContentValues v = new ContentValues();
            v.put("dur", dur);
            getWritableDatabase().update("tracks", v, "book=? AND doc=?",
                    new String[]{String.valueOf(book), doc});
        });
    }

    void saveSpeed(final long book, final float speed) {
        IO.execute(() -> {
            ContentValues v = new ContentValues();
            v.put("speed", speed);
            getWritableDatabase().update("books", v, "id=?", new String[]{String.valueOf(book)});
        });
    }

    /** Back to the first file, every file unplayed. */
    void restart(final long book, final String firstDoc) {
        IO.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            String id = String.valueOf(book);
            db.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                v.put("track", firstDoc);
                v.put("pos", 0);
                db.update("books", v, "id=?", new String[]{id});
                ContentValues t = new ContentValues();
                t.put("pos", 0);
                t.put("done", 0);
                db.update("tracks", t, "book=?", new String[]{id});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        });
    }

    /**
     * Takes a book off the list. The row stays, so a rescan does not bring it
     * back and adding the folder again later restores your place.
     */
    void hide(final long book) {
        IO.execute(() -> {
            ContentValues v = new ContentValues();
            v.put("hidden", 1);
            getWritableDatabase().update("books", v, "id=?", new String[]{String.valueOf(book)});
        });
    }
}
