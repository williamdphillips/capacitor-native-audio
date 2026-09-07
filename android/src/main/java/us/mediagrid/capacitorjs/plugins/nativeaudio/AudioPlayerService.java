package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Foreground MediaSessionService so lock-screen / notification / BT media controls work.
 */
public class AudioPlayerService extends MediaSessionService {

    private static final String TAG = "AudioPlayerService";
    public static final String PLAYBACK_CHANNEL_ID = "wave_playback_channel";

    private MediaSession mediaSession;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service starting");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Log.i(TAG, "Task removed");
        if (mediaSession != null) {
            Player player = mediaSession.getPlayer();
            if (player.getPlayWhenReady()) {
                player.pause();
            }
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");
        PlaylistAudioManager.getInstance(this).onServiceDestroyed();
        mediaSession = null;
        super.onDestroy();
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
