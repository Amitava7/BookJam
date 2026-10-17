package com.bookjam;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The library: every book you have added, the one you were listening to
 * first, with how far through each you are. The bar at the bottom resumes
 * the last book exactly where it stopped, even after the app was killed.
 */
public final class MainActivity extends Activity implements Player.Listener {

    private static final int PICK = 1;
    private static boolean rootsChecked;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Library lib;
    private Player player;

    private LinearLayout content, toolbar, group, empty, mini;
    private View header;
    private TextView bigTitle, count, smallTitle, status, footnote, miniTitle, miniBook;
    private ImageView miniArt;
    private ImageButton miniPlay;
    private int headerHeight;
    private long miniBookId = -1;
    private int scanning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        lib = Library.get(this);
        player = Player.get(this);
        build();
        player.addListener(this);
        if (!rootsChecked) {
            rootsChecked = true;
            final Context app = getApplicationContext();
            Scanner.BACKGROUND.execute(() -> {
                List<Long> ids = Scanner.rescanRoots(app);
                if (ids.isEmpty()) return;
                Library.IO.execute(() -> runOnUiThread(this::refresh));
                for (long id : ids) Scanner.measure(app, id);
                Library.IO.execute(() -> runOnUiThread(this::refresh));
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        player.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onPlayerChanged() {
        bindMini();
    }

    @Override
    public void onPlayerClosed() {
        finishAndRemoveTask();
    }

    // ---- layout -------------------------------------------------------------

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        content = Ui.column(this);
        int side = Ui.dp(this, 14);
        content.setPadding(side, 0, side, 0);

        // One UI's big title: a third of the screen, so the list starts
        // where a thumb can reach, folding into the bar above as you scroll.
        headerHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.30f);
        LinearLayout h = Ui.column(this);
        h.setGravity(Gravity.CENTER);
        bigTitle = Ui.text(this, "Library", 36f, Ui.TEXT, 700);
        bigTitle.setGravity(Gravity.CENTER);
        h.addView(bigTitle, Ui.lp(Ui.MATCH, Ui.WRAP));
        count = Ui.text(this, "", 15f, Ui.DIM, 500);
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, Ui.dp(this, 10), 0, 0);
        h.addView(count, Ui.lp(Ui.MATCH, Ui.WRAP));
        header = h;
        content.addView(header, Ui.lp(Ui.MATCH, headerHeight));

        status = Ui.text(this, "", 14f, Ui.ACCENT, 600);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, Ui.dp(this, 14));
        status.setVisibility(View.GONE);
        content.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP));

        group = Ui.column(this);
        group.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 26)));
        Ui.clipRound(group, Ui.dp(this, 26));
        content.addView(group, Ui.lp(Ui.MATCH, Ui.WRAP));

        footnote = Ui.text(this, "Touch and hold a book to start it over or remove it.",
                13f, Ui.DIM, 400);
        footnote.setGravity(Gravity.CENTER);
        footnote.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), 0);
        content.addView(footnote, Ui.lp(Ui.MATCH, Ui.WRAP));

        empty = emptyState();
        content.addView(empty, Ui.lp(Ui.MATCH, Ui.WRAP));

        scroll.addView(content, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(scroll, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        toolbar = Ui.row(this);
        toolbar.setMinimumHeight(Ui.dp(this, 56));
        toolbar.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 8), 0);
        smallTitle = Ui.line(this, "Library", 21f, Ui.TEXT, 700);
        smallTitle.setAlpha(0f);
        toolbar.addView(smallTitle, Ui.lp(0, Ui.WRAP, 1f));
        ImageButton add = Ui.iconButton(this, R.drawable.ic_add, 48, 26, 0, Ui.TEXT, "Add a folder");
        add.setOnClickListener(v -> pick());
        toolbar.addView(add);
        root.addView(toolbar, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        mini = miniPlayer();
        final FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP,
                Gravity.BOTTOM);
        root.addView(mini, ml);

        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> collapse(y));

        setContentView(root);
        Ui.onInsets(root, bars -> {
            int m = Ui.dp(this, 12);
            toolbar.setPadding(Ui.dp(this, 24) + bars.left, bars.top, Ui.dp(this, 8) + bars.right, 0);
            content.setPadding(side + bars.left, bars.top, side + bars.right,
                    bars.bottom + Ui.dp(this, 120));
            ml.setMargins(m + bars.left, 0, m + bars.right, m + bars.bottom);
            mini.setLayoutParams(ml);
        });
    }

    /** Fades the big title out and the bar title in as the list scrolls up. */
    private void collapse(int y) {
        float f = Math.max(0f, Math.min(1f, y / (headerHeight * 0.55f)));
        bigTitle.setAlpha(1f - f);
        count.setAlpha(1f - f);
        float t = Math.max(0f, Math.min(1f, (y - headerHeight * 0.45f) / (headerHeight * 0.25f)));
        smallTitle.setAlpha(t);
        toolbar.setBackgroundColor(Ui.withAlpha(Ui.BG, Math.round(t * 0xF2)));
    }

    private LinearLayout emptyState() {
        LinearLayout e = Ui.column(this);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_stat);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.FAINT));
        e.addView(icon, Ui.lp(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView t = Ui.text(this, "No books yet", 21f, Ui.TEXT, 700);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        e.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView d = Ui.text(this, "Pick a folder of audio files, or a folder of book folders. "
                + "Each folder becomes a book named after it, and its files play in number "
                + "order: 1, 2, 3 … 10, 11.", 15f, Ui.DIM, 400);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(0, 1.15f);
        e.addView(d, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView b = Ui.bigButton(this, "Add a folder", Ui.ACCENT, Ui.ON_ACCENT);
        b.setOnClickListener(v -> pick());
        LinearLayout.LayoutParams bl = Ui.lp(Ui.WRAP, Ui.WRAP);
        bl.topMargin = Ui.dp(this, 26);
        e.addView(b, bl);
        return e;
    }

    private LinearLayout miniPlayer() {
        LinearLayout m = Ui.row(this);
        m.setBackground(Ui.ripple(Ui.CARD, Ui.dp(this, 26)));
        m.setElevation(Ui.dp(this, 8));
        int p = Ui.dp(this, 10);
        m.setPadding(p, p, p, p);
        m.setContentDescription("Now playing");
        m.setOnClickListener(v -> openPlayer(miniBookId));
        miniArt = new ImageView(this);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.clipRound(miniArt, Ui.dp(this, 14));
        m.addView(miniArt, Ui.lp(Ui.dp(this, 54), Ui.dp(this, 54)));
        LinearLayout text = Ui.column(this);
        text.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 10), 0);
        miniTitle = Ui.line(this, "", 16f, Ui.TEXT, 600);
        miniBook = Ui.line(this, "", 13f, Ui.DIM, 400);
        miniBook.setPadding(0, Ui.dp(this, 4), 0, 0);
        text.addView(miniTitle);
        text.addView(miniBook);
        m.addView(text, Ui.lp(0, Ui.WRAP, 1f));
        miniPlay = Ui.iconButton(this, R.drawable.ic_play, 52, 26, Ui.TEXT, Ui.BG, "Play");
        miniPlay.setOnClickListener(v -> {
            if (!player.isPlaying()) PlayerActivity.askForNotifications(this);
            if (player.book == null && miniBookId >= 0) player.open(miniBookId, true);
            else player.toggle();
        });
        m.addView(miniPlay);
        m.setVisibility(View.GONE);
        return m;
    }

    // ---- content ------------------------------------------------------------

    private void refresh() {
        if (isDestroyed()) return;
        List<Library.Book> books = lib.books();
        group.removeAllViews();
        for (int i = 0; i < books.size(); i++) {
            Library.Book b = books.get(i);
            if (player.book != null && player.book.id == b.id) {
                b.track = player.book.track;
                b.pos = player.position();
                b.speed = player.book.speed;
            }
            Library.progress(b, lib.tracks(b.id));
            if (i > 0) group.addView(Ui.divider(this, 98));
            group.addView(row(b));
        }
        boolean none = books.isEmpty();
        group.setVisibility(none ? View.GONE : View.VISIBLE);
        footnote.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none && scanning == 0 ? View.VISIBLE : View.GONE);
        count.setText(none ? "Your audiobooks, one folder each"
                : books.size() == 1 ? "1 book" : books.size() + " books");
        bindMini();
    }

    private View row(final Library.Book b) {
        LinearLayout row = Ui.row(this);
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, Ui.dp(this, 18), p);
        row.setBackground(Ui.ripple(0, 0));
        row.setOnClickListener(v -> openPlayer(b.id));
        row.setOnLongClickListener(v -> {
            options(b);
            return true;
        });

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.clipRound(cover, Ui.dp(this, 14));
        Covers.into(cover, b, 256, null);
        row.addView(cover, Ui.lp(Ui.dp(this, 70), Ui.dp(this, 70)));

        LinearLayout text = Ui.column(this);
        text.setPadding(Ui.dp(this, 16), 0, 0, 0);
        boolean playing = player.book != null && player.book.id == b.id && player.isPlaying();
        TextView name = Ui.text(this, b.name, 17f, playing ? Ui.ACCENT : Ui.TEXT, 600);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        TextView detail = Ui.line(this, detail(b, playing), 13f, Ui.DIM, 400);
        detail.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 9));
        text.addView(detail);
        text.addView(progressBar(fraction(b)), Ui.lp(Ui.MATCH, Ui.dp(this, 4)));
        row.addView(text, Ui.lp(0, Ui.WRAP, 1f));
        return row;
    }

    private static String detail(Library.Book b, boolean playing) {
        String files = b.count == 1 ? "1 file" : b.count + " files";
        String s;
        if (b.count == 0) s = "No audio files found";
        else if (b.finished()) s = "Finished";
        else if (!b.started()) s = files + (b.timed ? " · " + Fmt.span(b.total) : "");
        else s = "File " + (b.index + 1) + " of " + b.count
                    + (b.timed ? " · " + Fmt.span((long) ((b.total - b.heard) / b.speed)) + " left" : "");
        return playing ? "Playing · " + s : s;
    }

    private static float fraction(Library.Book b) {
        if (b.count == 0) return 0f;
        if (b.timed && b.total > 0) return Math.min(1f, b.heard / (float) b.total);
        return b.index / (float) b.count;
    }

    private View progressBar(float f) {
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(Ui.withAlpha(Ui.TEXT, 0x18), Ui.dp(this, 2)));
        View fill = new View(this);
        fill.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 2)));
        bar.addView(fill, Ui.lp(0, Ui.MATCH, f));
        bar.addView(new View(this), Ui.lp(0, Ui.MATCH, 1f - f));
        return bar;
    }

    private void bindMini() {
        if (mini == null || isDestroyed()) return;
        Library.Book b = player.book != null ? player.book : lib.last();
        if (b == null) {
            mini.setVisibility(View.GONE);
            miniBookId = -1;
            return;
        }
        mini.setVisibility(View.VISIBLE);
        if (b.id != miniBookId) {
            miniBookId = b.id;
            Covers.into(miniArt, b, 160, null);
        }
        String chapter = "";
        if (player.book != null) {
            chapter = player.title();
        } else {
            for (Library.Track t : lib.tracks(b.id)) {
                if (t.doc.equals(b.track)) chapter = Fmt.title(t.name);
            }
        }
        miniTitle.setText(chapter.isEmpty() ? b.name : chapter);
        miniBook.setText(b.name);
        boolean playing = player.book != null && player.isPlaying();
        miniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlay.setContentDescription(playing ? "Pause" : "Play");
    }

    private void openPlayer(long id) {
        if (id < 0) return;
        startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_BOOK, id));
    }

    private void options(final Library.Book b) {
        final Sheets.Sheet s = Sheets.sheet(this, b.name, null);
        TextView restart = Sheets.choice(this, "Play from the beginning", Ui.TEXT);
        restart.setOnClickListener(v -> {
            s.dialog.dismiss();
            if (player.book != null && player.book.id == b.id) {
                player.restart();
            } else {
                List<Library.Track> ts = lib.tracks(b.id);
                if (!ts.isEmpty()) lib.restart(b.id, ts.get(0).doc);
            }
            Library.IO.execute(() -> runOnUiThread(this::refresh));
        });
        s.body.addView(restart, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView remove = Sheets.choice(this, "Remove from library", Ui.RED);
        remove.setOnClickListener(v -> {
            s.dialog.dismiss();
            player.forget(b.id);
            lib.hide(b.id);
            Library.IO.execute(() -> runOnUiThread(this::refresh));
        });
        s.body.addView(remove, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView note = Ui.text(this, "Removing leaves the files where they are. Add the folder "
                + "again and the book comes back at the same place.", 13f, Ui.DIM, 400);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        s.body.addView(note);
        s.show();
    }

    // ---- adding folders -----------------------------------------------------

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, startFolder());
        try {
            startActivityForResult(i, PICK);
        } catch (ActivityNotFoundException e) {
            say("This phone has no folder picker.");
        }
    }

    /** The picker opens where you picked last time, or at the top of the phone's storage. */
    private Uri startFolder() {
        List<Uri> roots = lib.roots();
        if (!roots.isEmpty()) {
            Uri t = roots.get(roots.size() - 1);
            return DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            say("BookJam was not given access to that folder.");
            return;
        }
        scanning++;
        say("Looking for audio files…");
        empty.setVisibility(View.GONE);
        final Context app = getApplicationContext();
        Scanner.BACKGROUND.execute(() -> {
            final List<Long> ids = Scanner.addPicked(app, tree);
            Library.IO.execute(() -> runOnUiThread(() -> {
                scanning--;
                refresh();
                say(ids.isEmpty() ? "No audio files in that folder."
                        : ids.size() == 1 ? "Added 1 book." : "Added " + ids.size() + " books.");
            }));
            for (long id : ids) Scanner.measure(app, id);
            Library.IO.execute(() -> runOnUiThread(this::refresh));
        });
    }

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private void say(String message) {
        if (isDestroyed()) return;
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideStatus);
        if (scanning == 0) handler.postDelayed(hideStatus, 5000);
    }
}
