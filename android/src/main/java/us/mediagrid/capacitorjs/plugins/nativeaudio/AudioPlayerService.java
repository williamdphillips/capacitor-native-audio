package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Foreground MediaSessionService so lock-screen / notification / BT media controls work.
 *
 * Android requires startForeground() soon after startForegroundService(); we promote
 * immediately so Media3's later media notification can replace the placeholder.
 */
public class AudioPlayerService extends MediaSessionService {

    private static final String TAG = "AudioPlayerService";
    public static final String PLAYBACK_CHANNEL_ID = "wave_playback_channel";
    /** Must match {@link DefaultMediaNotificationProvider#DEFAULT_NOTIFICATION_ID}. */
    private static final int NOTIFICATION_ID = 1001;

    private MediaSession mediaSession;
    private boolean startedInForeground = false;

    @Override
    @OptIn(markerClass = UnstableApi.class)
    public void onCreate() {
        Log.i(TAG, "Service being created");
        super.onCreate();
        createNotificationChannel();

        setMediaNotificationProvider(
            new DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .build()
        );

        PlaylistAudioManager manager = PlaylistAudioManager.getInstance(this);
        manager.onServiceCreated(this);
        mediaSession = manager.getMediaSession();

        // Satisfy startForegroundService() contract even before Media3 posts its media UI.
        ensureStartedInForeground("Wave", "Preparing playback…");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service starting");
        ensureStartedInForeground("Wave", "Playing");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Log.i(TAG, "Task removed");
        // Keep audio going in the background when the user swipes the task away.
        if (mediaSession == null) {
            stopSelf();
            return;
        }
        Player player = mediaSession.getPlayer();
        if (!player.getPlayWhenReady() && !player.isPlaying()) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");
        PlaylistAudioManager.getInstance(this).onServiceDestroyed();
        mediaSession = null;
        startedInForeground = false;
        super.onDestroy();
    }

    private void ensureStartedInForeground(String title, String text) {
        if (startedInForeground) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(this, PLAYBACK_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(getApplicationInfo().icon != 0
                ? getApplicationInfo().icon
                : android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            startedInForeground = true;
            Log.i(TAG, "Started in foreground");
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForeground", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "Wave Playback",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Now playing controls");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
