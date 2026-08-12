package com.KV;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

public class FloatService extends Service {

    private static final String TAG = "KV_FloatService";
    private static final String CHANNEL_ID = "keyviewer_overlay";
    private static final int NOTIFICATION_ID = 4102;

    private static volatile FloatService instance;

    private FloatView floatView;
    private KeyView keyView;
    private RishInputListener inputListener;

    public static FloatService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, createNotification());
        keyView = new KeyView(this);
        keyView.setEditable(false);
        inputListener = new RishInputListener(this, keyView);
        floatView = new FloatView(this, keyView);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            try {
                floatView.attachToWindow();
                startInputListening();
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to attach overlay", e);
                GlobalSettings settings = GlobalSettings.getInstance(this);
                settings.floatWindowEnabled = false;
                settings.save();
                stopSelf();
            }
        } else {
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "KeyViewer overlay", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps keyboard input visualization running");
            manager.createNotificationChannel(channel);
        }

        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("KeyViewer is running")
                .setContentText("Tap to open overlay settings")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    public void startInputListening() {
        if (inputListener != null) {
            inputListener.startListening();
        }
    }

    public void stopInputListening() {
        if (inputListener != null) {
            inputListener.stopListening();
        }
    }

    public void pauseInputListening() {
        if (inputListener != null) {
            inputListener.pause();
        }
    }

    public void resumeInputListening() {
        if (inputListener != null) {
            inputListener.resume();
        }
    }

    public void reloadConfiguration() {
        if (keyView == null) {
            return;
        }
        stopInputListening();
        keyView.getKeyManager().loadFromPreferences();
        keyView.refreshGlobalSettings();
        if (floatView != null) {
            floatView.updateScale();
        }
        startInputListening();
    }

    @Override
    public void onDestroy() {
        stopInputListening();
        if (keyView != null) {
            keyView.stopRefreshing();
            keyView.getKeyManager().flushPendingCountSaves();
        }
        if (floatView != null) {
            floatView.detachFromWindow();
        }
        instance = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public FloatView getFloatView() {
        return floatView;
    }

    public void updateScale() {
        if (keyView != null) {
            keyView.refreshGlobalSettings();
        }
        if (floatView != null) {
            floatView.updateScale();
        }
    }
}
