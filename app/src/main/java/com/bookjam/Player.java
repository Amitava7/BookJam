package com.bookjam;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plays one book at a time and remembers where you are in it.
 *
 * Lives as long as the process, independent of any screen. PlaybackService
 * only keeps the process in the foreground while this is playing, and the
 * activities only watch and send commands. Everything here runs on the main
 * thread.
 */
final class Player {

    interface Listener {
        /** The book, the file, play or pause, the speed or the sleep timer changed. */
        void onPlayerChanged();

        /** The sleep timer ran out: playback has stopped and the app should close. */
        default void onPlayerClosed() {
        }
    }

    static final long SKIP = 10_000;
    static final float MIN_SPEED = 0.5f, MAX_SPEED = 3f;

    private static final String TAG = "BookJam";
    private static final long SAVE_EVERY = 3_000;
    private static final long FADE = 15_000;
    private static final long UNDO_FOR = 12_000;
    private static final long BIG_JUMP = 30_000;
    private static final long SEEK_PATIENCE = 3_000;
    static final String CUSTOM_REWIND = "com.bookjam.REWIND";
    static final String CUSTOM_FORWARD = "com.bookjam.FORWARD";

    private static Player sInstance;

    static synchronized Player get(Context c) {
        if (sInstance == null) sInstance = new Player(c.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final Library lib;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private final AudioFocusRequest focus;
    final MediaSession session;
    private final List<Listener> listeners = new ArrayList<>();

    /** The loaded book, or null. */
    Library.Book book;
    List<Library.Track> tracks = Collections.emptyList();
    int index;
    Bitmap art;
    /** Why the current file will not play, or null. */
    String error;

    private MediaPlayer mp;
    private boolean prepared;
    private boolean wantPlay;           // playing, or about to be once the file is ready
    private long startAt;               // where to start once prepared; negative counts from the end
    private long seekTarget = -1;       // the newest seek, until the player gets there
    private long seekAt;                // when it was asked for
    private boolean resumeOnGain;       // paused for a phone call or a navigation prompt
    private boolean noisyRegistered;
    private long lastSave;
    private String metaKey;

    private int undoIndex = -1;
    private long undoPos, undoUntil;

    private long sleepAt;               // elapsedRealtime when the timer ends, 0 when off
    private boolean sleepEndOfTrack;

    private Player(Context c) {
        ctx = c;
        lib = Library.get(c);
        audio = c.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::onFocus, main)
                .build();
        session = new MediaSession(c, TAG);
        session.setCallback(new SessionCallback(), main);
        Intent open = new Intent(c, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- what the screens read ----------------------------------------------

    /** Playing, or getting the file ready to play. */
    boolean isPlaying() {
        return wantPlay;
    }

    long position() {
        if (book == null || tracks.isEmpty()) return 0;
        if (mp != null && prepared) {
            // Quick taps each start a seek before the last one has finished;
            // count from where the newest is going, not where the player is.
            if (seekTarget >= 0 && SystemClock.elapsedRealtime() - seekAt < SEEK_PATIENCE) {
                return seekTarget;
            }
            seekTarget = -1;
            return mp.getCurrentPosition();
        }
        if (startAt >= 0) return startAt;
        return Math.max(0, tracks.get(index).dur + startAt);
    }

    long duration() {
        if (book == null || tracks.isEmpty()) return 0;
        if (mp != null && prepared) {
            long d = mp.getDuration();
            if (d > 0) return d;
        }
        return tracks.get(index).dur;
    }

    String title(int i) {
        return i >= 0 && i < tracks.size() ? Fmt.title(tracks.get(i).name) : "";
    }

    String title() {
        return title(index);
    }

    /** Listening time left in the whole book at the current speed, or -1 if not known yet. */
    long bookLeft() {
        if (book == null || tracks.isEmpty()) return -1;
        long d = duration();
        if (d <= 0) return -1;
        long left = d - position();
        for (int i = index + 1; i < tracks.size(); i++) {
            long t = tracks.get(i).dur;
            if (t <= 0) return -1;
            left += t;
        }
        return (long) (Math.max(0, left) / book.speed);
    }

    long sleepLeft() {
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    boolean sleepsAtEndOfTrack() {
        return sleepEndOfTrack;
    }

    boolean canUndo() {
        return book != null && undoIndex >= 0 && undoIndex < tracks.size()
                && SystemClock.elapsedRealtime() < undoUntil;
    }

    String undoLabel() {
        String where = Fmt.clock(undoPos);
        return undoIndex == index ? "Undo · back to " + where
                : "Undo · back to " + title(undoIndex) + " " + where;
    }

    // ---- loading ------------------------------------------------------------

    /** Loads a book at the place you left it. Does nothing if it is already loaded. */
    void open(long id, boolean play) {
        if (book != null && book.id == id) {
            if (play) play();
            return;
        }
        Library.Book b = lib.book(id);
        if (b == null) return;
        if (book != null) {
            saveNow();
            pause(true);
        }
        release();
        book = b;
        tracks = lib.tracks(id);
        index = 0;
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).doc.equals(b.track)) index = i;
        art = Covers.art(ctx, b);
        undoIndex = -1;
        metaKey = null;
        session.setActive(true);
        load(index, b.pos, play);
        refreshFromDisk(b);
    }

    /** Picks up files added to or removed from the folder since it was last read. */
    private void refreshFromDisk(final Library.Book b) {
        Scanner.BACKGROUND.execute(() -> {
            final boolean ok = Scanner.refreshBook(ctx, b);
            final List<Library.Track> fresh = ok ? lib.tracks(b.id) : null;
            main.post(() -> {
                if (book != b) return;
                if (!ok) {
                    if (tracks.isEmpty()) {
                        error = "Can't open this folder. It may have been moved or deleted.";
                        publish();
                    }
                    return;
                }
                String current = tracks.isEmpty() ? null : tracks.get(index).doc;
                int at = -1;
                for (int i = 0; i < fresh.size(); i++) if (fresh.get(i).doc.equals(current)) at = i;
                // Copy across what this session knows better than the database.
                for (Library.Track f : fresh) {
                    for (Library.Track t : tracks) {
                        if (t.doc.equals(f.doc)) {
                            f.pos = t.pos;
                            f.done = t.done;
                            if (t.dur > 0) f.dur = t.dur;
                        }
                    }
                }
                if (current != null && at < 0) return;   // the playing file vanished; keep going
                tracks = fresh;
                if (current == null) {
                    if (!tracks.isEmpty()) load(0, 0, wantPlay);
                } else {
                    index = at;
                }
                publish();
                measure(b);
            });
        });
    }

    /** Works out file lengths in the background, for the time left in the book. */
    private void measure(final Library.Book b) {
        Scanner.BACKGROUND.execute(() -> {
            final Map<String, Long> got = Scanner.measure(ctx, b.id);
            if (got.isEmpty()) return;
            main.post(() -> {
                if (book != b) return;
                for (Library.Track t : tracks) {
                    Long d = got.get(t.doc);
                    if (d != null && t.dur <= 0) t.dur = d;
                }
                publish();
            });
        });
    }

    /** Opens file i and starts at from ms; a negative from counts back from its end. */
    private void load(int i, long from, boolean play) {
        release();
        error = null;
        if (tracks.isEmpty()) {
            wantPlay = false;
            error = "There are no audio files in this folder.";
            publish();
            return;
        }
        index = Math.max(0, Math.min(i, tracks.size() - 1));
        Library.Track t = tracks.get(index);
        wantPlay = play;
        prepared = false;
        startAt = from;
        seekTarget = -1;
        book.track = t.doc;
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        p.setAudioAttributes(attrs);
        p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
        p.setOnPreparedListener(x -> {
            if (x == mp) onPrepared();
        });
        p.setOnCompletionListener(x -> {
            if (x == mp) onCompleted();
        });
        p.setOnSeekCompleteListener(x -> {
            if (x != mp) return;
            long at = x.getCurrentPosition();
            Log.i(TAG, "seek done at " + at + ", wanted " + seekTarget);
            // An earlier seek can finish after a newer one was asked for; only
            // let go of the target once the player has actually reached it.
            if (seekTarget >= 0 && Math.abs(at - seekTarget) < 1500) seekTarget = -1;
        });
        p.setOnErrorListener((x, what, extra) -> {
            if (x == mp) onError("error " + what + "/" + extra);
            return true;
        });
        try {
            p.setDataSource(ctx, t.uri(book.treeUri()));
            p.prepareAsync();
        } catch (Exception e) {
            onError(String.valueOf(e));
            return;
        }
        saveNow();
        publish();
    }

    private void onPrepared() {
        prepared = true;
        Library.Track t = tracks.get(index);
        long d = mp.getDuration();
        if (d > 0 && d != t.dur) {
            t.dur = d;
            lib.saveDuration(book.id, t.doc, d);
        }
        long from = startAt < 0 ? (d > 0 ? Math.max(0, d + startAt) : 0) : startAt;
        if (d > 0 && from >= d) from = Math.max(0, d - 1000);
        startAt = 0;
        if (from > 0) {
            seekTarget = from;
            seekAt = SystemClock.elapsedRealtime();
            mp.seekTo(from, MediaPlayer.SEEK_CLOSEST);
        }
        if (wantPlay) startPlayback();
        publish();
    }

    private void onCompleted() {
        finishTrack(index);
        boolean more = index < tracks.size() - 1;
        if (sleepEndOfTrack) {
            sleepEndOfTrack = false;
            if (more) load(index + 1, 0, false);
            close();
            return;
        }
        if (more) {
            load(index + 1, 0, true);
            return;
        }
        // The end of the book.
        wantPlay = false;
        unregisterNoisy();
        audio.abandonAudioFocusRequest(focus);
        saveNow();
        publish();
    }

    private void onError(String why) {
        Log.w(TAG, "cannot play " + title() + ": " + why);
        release();
        wantPlay = false;
        unregisterNoisy();
        error = "Can't play “" + title() + "”.";
        publish();
    }

    private void release() {
        if (mp == null) return;
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        seekTarget = -1;
        try {
            p.release();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    // ---- commands -----------------------------------------------------------

    void play() {
        if (book == null) {
            Library.Book last = lib.last();
            if (last != null) open(last.id, true);
            return;
        }
        if (tracks.isEmpty()) return;
        resumeOnGain = false;
        // At the very end of the book, play means listen again from the start.
        long d = duration();
        if (index == tracks.size() - 1 && d > 0 && position() >= d - 1500 && mp != null && prepared) {
            load(0, 0, true);
            return;
        }
        if (mp == null) {
            load(index, book.pos, true);
            return;
        }
        wantPlay = true;
        if (prepared) startPlayback();
        saveNow();
        publish();
    }

    void pause() {
        pause(true);
    }

    /** byUser is false when another app took the audio for a while. */
    private void pause(boolean byUser) {
        wantPlay = false;
        if (byUser) resumeOnGain = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        if (byUser) audio.abandonAudioFocusRequest(focus);
        unregisterNoisy();
        saveNow();
        publish();
    }

    void toggle() {
        if (wantPlay) pause();
        else play();
    }

    private void startPlayback() {
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            wantPlay = false;
            return;
        }
        float v = volume();
        mp.setVolume(v, v);
        mp.start();
        applySpeed();
        if (!noisyRegistered) {
            // Headphones unplugged or Bluetooth dropped: pause rather than
            // carry on out loud.
            ctx.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    Context.RECEIVER_EXPORTED);
            noisyRegistered = true;
        }
        lastSave = SystemClock.elapsedRealtime();
        tick();
    }

    private void applySpeed() {
        // Only while playing: on a paused player, setting a speed starts it.
        if (mp == null || !prepared || !mp.isPlaying()) return;
        try {
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(book.speed));
        } catch (RuntimeException e) {
            Log.w(TAG, "speed " + book.speed + " refused", e);
        }
    }

    void setSpeed(float s) {
        if (book == null) return;
        s = Fmt.snapSpeed(s);
        if (s == book.speed) return;
        book.speed = s;
        applySpeed();
        lib.saveSpeed(book.id, s);
        publish();
    }

    /** Jumps within the current file. */
    void seekTo(long ms) {
        if (book == null || tracks.isEmpty()) return;
        long d = duration();
        if (d > 0) ms = Math.min(ms, d - 200);
        ms = Math.max(0, ms);
        if (mp != null && prepared) {
            seekTarget = ms;
            seekAt = SystemClock.elapsedRealtime();
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        } else {
            startAt = ms;
        }
        saveNow();
        publish();
    }

    /** The slider: like seekTo, but a long drag can be undone. */
    void scrubTo(long ms) {
        if (Math.abs(ms - position()) > BIG_JUMP) rememberUndo();
        seekTo(ms);
    }

    /**
     * Back or forward by delta ms, running on into the next or previous file
     * when it passes either end of this one. Called once per tap, so three
     * quick taps on forward are 30 seconds.
     */
    void seekBy(long delta) {
        if (book == null || tracks.isEmpty()) return;
        if (mp != null && !prepared) {
            // Still opening the file: move where it will start instead.
            startAt = startAt < 0 ? Math.min(-1, startAt + delta) : Math.max(0, startAt + delta);
            publish();
            return;
        }
        long from = position(), target = from + delta, d = duration();
        Log.i(TAG, "skip " + delta + " ms: " + from + " -> " + target + " of " + d
                + (seekTarget >= 0 ? " (seek to " + seekTarget + " pending)" : ""));
        if (target < 0 && index > 0) {
            keepTrackPosition();
            load(index - 1, target, wantPlay);
            return;
        }
        if (d > 0 && target >= d && index < tracks.size() - 1) {
            finishTrack(index);
            load(index + 1, target - d, wantPlay);
            return;
        }
        seekTo(target);
    }

    /** Plays file i from where you last left it, and offers to undo the jump. */
    void jumpTo(int i) {
        if (book == null || i < 0 || i >= tracks.size()) return;
        if (i == index) {
            if (!wantPlay) play();
            return;
        }
        rememberUndo();
        keepTrackPosition();
        Library.Track t = tracks.get(i);
        long from = t.done || (t.dur > 0 && t.pos > t.dur - 5000) ? 0 : t.pos;
        load(i, from, true);
    }

    void undo() {
        if (!canUndo()) return;
        int i = undoIndex;
        long p = undoPos;
        undoIndex = -1;
        if (i == index) {
            seekTo(p);
        } else {
            keepTrackPosition();
            load(i, p, wantPlay);
        }
    }

    private void rememberUndo() {
        undoIndex = index;
        undoPos = position();
        undoUntil = SystemClock.elapsedRealtime() + UNDO_FOR;
    }

    /** Back to the first file of the book, everything unplayed. */
    void restart() {
        if (book == null || tracks.isEmpty()) return;
        for (Library.Track t : tracks) {
            t.pos = 0;
            t.done = false;
        }
        lib.restart(book.id, tracks.get(0).doc);
        undoIndex = -1;
        load(0, 0, wantPlay);
    }

    /** Unloads the book if it is the one loaded, e.g. when it is removed from the library. */
    void forget(long id) {
        if (book == null || book.id != id) return;
        pause(true);
        unload();
    }

    // ---- the sleep timer ----------------------------------------------------

    void sleepIn(long ms) {
        sleepEndOfTrack = false;
        sleepAt = SystemClock.elapsedRealtime() + ms;
        restoreVolume();
        tick();
        publish();
    }

    void sleepAtEndOfTrack() {
        sleepAt = 0;
        sleepEndOfTrack = true;
        restoreVolume();
        publish();
    }

    void sleepOff() {
        sleepAt = 0;
        sleepEndOfTrack = false;
        restoreVolume();
        publish();
    }

    /** The last 15 seconds before the timer ends fade out, so the stop is not abrupt. */
    private float volume() {
        long left = sleepLeft();
        if (sleepAt == 0 || left >= FADE) return 1f;
        return Math.max(0f, left / (float) FADE);
    }

    private void restoreVolume() {
        if (mp != null && prepared) mp.setVolume(1f, 1f);
    }

    /** The timer ran out: stop, save, and close the app. */
    void close() {
        sleepAt = 0;
        sleepEndOfTrack = false;
        if (book != null) pause(true);
        // Screens close first, so none of them flashes up as the book unloads.
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerClosed();
        unload();
    }

    private void unload() {
        release();
        main.removeCallbacks(ticker);
        session.setActive(false);
        book = null;
        tracks = Collections.emptyList();
        index = 0;
        art = null;
        error = null;
        undoIndex = -1;
        metaKey = null;
        publish();
    }

    // ---- saving -------------------------------------------------------------

    /** Writes where you are. Cheap; called on every change and every few seconds. */
    void saveNow() {
        if (book == null || tracks.isEmpty()) return;
        long p = position();
        book.pos = p;
        Library.Track t = tracks.get(index);
        t.pos = p;
        t.done = false;
        lib.savePosition(book.id, t.doc, p, wantPlay);
        if (wantPlay) book.played = System.currentTimeMillis();
        lastSave = SystemClock.elapsedRealtime();
    }

    /** Remembers the current file's place before leaving it for another. */
    private void keepTrackPosition() {
        Library.Track t = tracks.get(index);
        t.pos = position();
        lib.saveTrack(book.id, t.doc, t.pos, t.done);
    }

    private void finishTrack(int i) {
        Library.Track t = tracks.get(i);
        t.done = true;
        t.pos = 0;
        lib.saveTrack(book.id, t.doc, 0, true);
    }

    private final Runnable ticker = this::tick;

    /** Once a second while playing or while a timer runs: save, count down, fade. */
    private void tick() {
        main.removeCallbacks(ticker);
        if (book == null) return;
        long now = SystemClock.elapsedRealtime();
        if (sleepAt > 0) {
            if (now >= sleepAt) {
                close();
                return;
            }
            if (mp != null && prepared) {
                float v = volume();
                mp.setVolume(v, v);
            }
        }
        if (wantPlay && now - lastSave >= SAVE_EVERY) {
            saveNow();
            updateSession();
        }
        if (wantPlay || sleepAt > 0) {
            main.postDelayed(ticker, sleepAt > 0 && sleepAt - now < FADE ? 250 : 1000);
        }
    }

    // ---- telling everyone ---------------------------------------------------

    private void publish() {
        updateSession();
        PlaybackService.sync(ctx);
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerChanged();
    }

    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_REWIND;

    private void updateSession() {
        if (book == null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 0f).build());
            return;
        }
        int state = error != null ? PlaybackState.STATE_ERROR
                : !wantPlay ? PlaybackState.STATE_PAUSED
                : prepared ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_BUFFERING;
        // With no skip-to-next or skip-to-previous, the lock screen and the
        // quick panel put these two in those slots: back and forward 10 s.
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, position(), state == PlaybackState.STATE_PLAYING ? book.speed : 0f,
                        SystemClock.elapsedRealtime())
                .addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_REWIND,
                        "Back 10 seconds", R.drawable.ic_replay_10).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_FORWARD,
                        "Forward 10 seconds", R.drawable.ic_forward_10).build());
        if (error != null) b.setErrorMessage(error);
        session.setPlaybackState(b.build());

        long d = duration();
        String key = book.id + "/" + index + "/" + d + "/" + tracks.size();
        if (!key.equals(metaKey)) {
            metaKey = key;
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, book.name)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, book.name)
                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, index + 1)
                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, tracks.size())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, d));
            if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(m.build());
        }
    }

    // ---- the outside world --------------------------------------------------

    private void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // A call or a navigation prompt. Spoken word is lost under
                // ducking, so pause, and carry on when it is over.
                if (wantPlay) {
                    resumeOnGain = true;
                    pause(false);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnGain) {
                    resumeOnGain = false;
                    play();
                }
                break;
            default:
                break;
        }
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
        }
    };

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        noisyRegistered = false;
        try {
            ctx.unregisterReceiver(noisy);
        } catch (IllegalArgumentException ignored) {
            // was not registered
        }
    }

    /** Lock screen, quick panel, watch, car and headphone buttons. */
    private final class SessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            play();
        }

        @Override
        public void onPause() {
            pause();
        }

        @Override
        public void onStop() {
            pause();
        }

        @Override
        public void onSeekTo(long pos) {
            seekTo(pos);
        }

        @Override
        public void onFastForward() {
            seekBy(SKIP);
        }

        @Override
        public void onRewind() {
            seekBy(-SKIP);
        }

        @Override
        public void onSkipToNext() {
            seekBy(SKIP);
        }

        @Override
        public void onSkipToPrevious() {
            seekBy(-SKIP);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (CUSTOM_REWIND.equals(action)) seekBy(-SKIP);
            else if (CUSTOM_FORWARD.equals(action)) seekBy(SKIP);
        }

        /**
         * Next and previous on headphones move 10 seconds, not a whole file,
         * so a stray double tap on an earbud never loses your chapter.
         */
        @Override
        public boolean onMediaButtonEvent(Intent intent) {
            KeyEvent k = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
            if (k != null) {
                boolean down = k.getAction() == KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0;
                switch (k.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                    case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                        if (down) seekBy(SKIP);
                        return true;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    case KeyEvent.KEYCODE_MEDIA_REWIND:
                        if (down) seekBy(-SKIP);
                        return true;
                    default:
                        break;
                }
            }
            return super.onMediaButtonEvent(intent);
        }
    }
}
