package com.bookjam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps BookJam running with the screen off or another app in front, and
 * shows the playback notification with back 10, play/pause and forward 10.
 *
 * It holds no state of its own: it mirrors Player. While playing it is a
 * foreground service; paused, it steps out of the foreground and leaves a
 * notification you can swipe away; with nothing loaded it stops.
 */
public final class PlaybackService extends Service {

    static final String SYNC = "com.bookjam.SYNC";
    static final String TOGGLE = "com.bookjam.TOGGLE";
    static final String REWIND = "com.bookjam.REWIND";
    static final String FORWARD = "com.bookjam.FORWARD";
    static final String DISMISS = "com.bookjam.DISMISS";

    private static final String CHANNEL = "playback";
    private static final int ID = 1;

    private static PlaybackService sRunning;
    private boolean foreground;

    /** Brings the notification and the foreground state in line with the player. */
    static void sync(Context c) {
        if (sRunning != null) {
            sRunning.refresh();
            return;
        }
        Player p = Player.get(c);
        if (p.book == null || !p.isPlaying()) return;
        try {
            c.startForegroundService(new Intent(c, PlaybackService.class).setAction(SYNC));
        } catch (RuntimeException e) {
            // Not allowed from the background on this occasion. Playback goes
            // on; it just is not protected from being stopped by the system.
            Log.w("BookJam", "could not start the playback service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = this;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("What is playing, with the controls");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sRunning = this;
        Player p = Player.get(this);
        String action = intent == null ? null : intent.getAction();
        if (SYNC.equals(action)) {
            // Started with startForegroundService: the system wants
            // startForeground now, whatever has happened since.
            goForeground(p.book == null ? placeholder() : build(p));
        } else if (TOGGLE.equals(action)) {
            p.toggle();
        } else if (REWIND.equals(action)) {
            p.seekBy(-Player.SKIP);
        } else if (FORWARD.equals(action)) {
            p.seekBy(Player.SKIP);
        } else if (DISMISS.equals(action) && !p.isPlaying()) {
            p.saveNow();
            shutDown();
            return START_NOT_STICKY;
        }
        refresh();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped out of recents. Keep playing if it is playing; otherwise go.
        Player p = Player.get(this);
        if (!p.isPlaying()) {
            p.saveNow();
            shutDown();
        }
    }

    @Override
    public void onDestroy() {
        if (sRunning == this) sRunning = null;
        Player.get(this).saveNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void shutDown() {
        if (sRunning == this) sRunning = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        getSystemService(NotificationManager.class).cancel(ID);
        stopSelf();
    }

    void refresh() {
        Player p = Player.get(this);
        if (p.book == null) {
            shutDown();
            return;
        }
        Notification n = build(p);
        if (p.isPlaying()) {
            goForeground(n);
        } else {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH);
                foreground = false;
            }
            getSystemService(NotificationManager.class).notify(ID, n);
        }
    }

    private void goForeground(Notification n) {
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            foreground = true;
        } catch (RuntimeException e) {
            Log.w("BookJam", "could not go foreground", e);
            getSystemService(NotificationManager.class).notify(ID, n);
        }
    }

    private PendingIntent command(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String label, String command, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label,
                command(command, code)).build();
    }

    private PendingIntent openPlayer() {
        Intent i = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(Player p) {
        boolean playing = p.isPlaying();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(p.title())
                .setContentText(p.book.name)
                .setLargeIcon(p.art)
                .setContentIntent(openPlayer())
                .setDeleteIntent(command(DISMISS, 4))
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_replay_10, "Back 10 seconds", REWIND, 1))
                .addAction(playing
                        ? action(R.drawable.ic_pause, "Pause", TOGGLE, 2)
                        : action(R.drawable.ic_play, "Play", TOGGLE, 2))
                .addAction(action(R.drawable.ic_forward_10, "Forward 10 seconds", FORWARD, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(p.session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification placeholder() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .build();
    }
}
