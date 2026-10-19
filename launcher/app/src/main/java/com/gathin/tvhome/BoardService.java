package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * The one piece that keeps running: it serves the upload page, checks email and the Telegram bot,
 * and pops a banner over whatever is playing when something arrives. Without it, notes and photos
 * would only appear while the screensaver happened to be on.
 *
 * Deliberately frugal for a 1 GB TV: a single background thread that wakes once a minute, one
 * socket waiting for the upload page, and no polling at all for sources you have not configured.
 */
public class BoardService extends Service {
    private static final String CHANNEL = "board";
    private static final long POLL_MS = 60_000;

    private static volatile BoardService instance;

    private HandlerThread worker;
    private Handler handler;
    private UploadServer upload;
    private OverlayNotifier notifier;
    private String announcedId;
    private boolean announceReady;   // don't announce the backlog found on the first poll

    /** Starts the service if it isn't already running. Safe to call repeatedly. */
    static void ensureRunning(Context context) {
        if (instance != null) return;
        if (!context.getResources().getBoolean(R.bool.board_enabled)) return;
        try {
            Intent i = new Intent(context.getApplicationContext(), BoardService.class);
            context.getApplicationContext().startForegroundService(i);
        } catch (Exception ignored) {
            // the board still works whenever the screensaver is on
        }
    }

    /** Pops the banner for whatever just landed on the board. */
    static void announceNewest(Context context) {
        BoardService s = instance;
        if (s == null) {
            ensureRunning(context);
            return;
        }
        s.announce();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Board.setKeepHours(getResources().getInteger(R.integer.board_keep_hours));
        notifier = new OverlayNotifier(this);
        startInForeground();

        upload = new UploadServer(this);
        Thread t = new Thread(upload, "upload-server");
        t.setDaemon(true);
        t.start();

        worker = new HandlerThread("board-poll");
        worker.start();
        handler = new Handler(worker.getLooper());
        handler.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (MailSource.configured(BoardService.this)) MailSource.poll(BoardService.this);
            } catch (Exception ignored) {
                // offline, or the mailbox refused: try again next minute
            }
            try {
                TelegramSource.poll(BoardService.this);
            } catch (Exception ignored) {
                // same
            }
            if (announceReady) announce();
            else {
                Board.Item newest = Board.newest();          // first pass: adopt without a banner
                announcedId = newest == null ? null : newest.id;
                announceReady = true;
            }
            if (handler != null) handler.postDelayed(this, POLL_MS);
        }
    };

    private void announce() {
        Board.Item newest = Board.newest();
        if (newest == null || newest.id.equals(announcedId)) return;
        announcedId = newest.id;
        notifier.announce(newest);
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.board_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.board_running))
                .setSmallIcon(R.drawable.status_dot)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (upload != null) upload.stop();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (worker != null) worker.quit();
        handler = null;
        instance = null;
        super.onDestroy();
    }
}
