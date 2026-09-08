package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Playlist-based audio manager matching the iOS AudioManager API used by Wave UI.
 * Playback + MediaSession live in {@link AudioPlayerService} for lock-screen / notification controls.
 */
public class PlaylistAudioManager {
    private static final String TAG = "PlaylistAudioManager";

    private static PlaylistAudioManager instance;

    public interface Listener {
        void onPlaybackStatusChange(boolean isPlaying);
        void onAudioEnd();
        void onPlayNext();
        void onPlayPrevious();
        void onPlaybackError(String audioId, String error);
        void onSeek(double time);
    }

    public static class AudioSourceItem {
        public final String audioId;
        public final String source;
        public final String title;
        public final String artist;
        public final String albumTitle;
        public final String artworkSource;

        public AudioSourceItem(
            String audioId,
            String source,
            String title,
            String artist,
            String albumTitle,
            String artworkSource
        ) {
            this.audioId = audioId;
            this.source = source;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.albumTitle = albumTitle != null ? albumTitle : "";
            this.artworkSource = artworkSource != null ? artworkSource : "";
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AudioSourceItem> audioSources = new ArrayList<>();

    private ExoPlayer player;
    private MediaSession mediaSession;
    private ForwardingPlayer sessionPlayer;
    private int currentIndex = -1;
    private Listener listener;
    private float volume = 1.0f;
    private boolean serviceReady = false;

    public static synchronized PlaylistAudioManager getInstance(Context context) {
        if (instance == null) {
            instance = new PlaylistAudioManager(context.getApplicationContext());
        }
        return instance;
    }

    private PlaylistAudioManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Called from {@link AudioPlayerService#onCreate()} — owns ExoPlayer + MediaSession.
     */
    public synchronized void onServiceCreated(AudioPlayerService service) {
        if (player == null) {
            player = new ExoPlayer.Builder(service)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
            player.setVolume(volume);
            player.addListener(createPlayerListener());
        }

        if (sessionPlayer == null) {
            sessionPlayer = new PlaylistForwardingPlayer(player);
        }

        if (mediaSession == null) {
            mediaSession = new MediaSession.Builder(service, sessionPlayer)
                .setId("wave-playlist-audio")
                .build();
        }

        serviceReady = true;
        Log.i(TAG, "AudioPlayerService ready with MediaSession");
    }

    public synchronized void onServiceDestroyed() {
        serviceReady = false;
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        sessionPlayer = null;
        if (player != null) {
            player.release();
            player = null;
        }
        currentIndex = -1;
        Log.i(TAG, "AudioPlayerService destroyed");
    }

    @Nullable
    public MediaSession getMediaSession() {
        return mediaSession;
    }

    private Player.Listener createPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (listener != null) {
                    listener.onPlaybackStatusChange(isPlaying);
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                // Match iOS: do not auto-advance natively — JS handles next via onAudioEnd
                if (playbackState == Player.STATE_ENDED && listener != null) {
                    listener.onAudioEnd();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                String audioId = currentIndex >= 0 && currentIndex < audioSources.size()
                    ? audioSources.get(currentIndex).audioId
                    : "";
                String message = error.getMessage() != null ? error.getMessage() : "Playback error";
                Log.e(TAG, "Playback error for " + audioId + ": " + message, error);
                if (listener != null) {
                    listener.onPlaybackError(audioId, message);
                }
            }
        };
    }

    /** Ensure MediaSessionService is running so lock-screen controls work. */
    public void ensureServiceStarted() {
        Intent intent = new Intent(appContext, AudioPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private void awaitServiceReady() throws Exception {
        ensureServiceStarted();
        if (serviceReady && player != null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (serviceReady && player != null) {
                return;
            }
            Thread.sleep(50);
        }
        if (!serviceReady || player == null) {
            throw new Exception("AudioPlayerService failed to start");
        }
    }

    public void setAudioSources(List<AudioSourceItem> sources) {
        String playingId = getCurrentAudioId();
        if (playingId == null && player != null && player.getMediaItemCount() > 0) {
            MediaItem current = player.getCurrentMediaItem();
            if (current != null && current.mediaId != null && !current.mediaId.isEmpty()) {
                playingId = current.mediaId;
            }
        }

        audioSources.clear();
        if (sources != null) {
            audioSources.addAll(sources);
        }

        if (playingId != null) {
            currentIndex = indexOf(playingId);
        } else {
            currentIndex = -1;
        }
        Log.d(TAG, "setAudioSources: " + audioSources.size() + " tracks, currentIndex=" + currentIndex);
    }

    /** Match iOS: append sources without clearing the current playlist / index. */
    public void createMultiple(List<AudioSourceItem> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        int added = 0;
        for (AudioSourceItem source : sources) {
            if (indexOf(source.audioId) >= 0) {
                Log.d(TAG, "Skipping add: audio source " + source.audioId + " already exists");
                continue;
            }
            audioSources.add(source);
            added++;
        }
        Log.d(TAG, "createMultiple: added " + added + " sources, total=" + audioSources.size()
            + ", currentIndex=" + currentIndex);
    }

    public void removeAudioSources(List<String> audioIds) {
        if (audioIds == null || audioIds.isEmpty()) {
            return;
        }
        String currentId = getCurrentAudioId();
        for (String audioId : audioIds) {
            if (currentId != null && currentId.equals(audioId)) {
                // Match iOS: never remove the currently playing track
                Log.d(TAG, "Skipping removal of currently playing audio source: " + audioId);
                continue;
            }
            int index = indexOf(audioId);
            if (index < 0) {
                continue;
            }
            audioSources.remove(index);
            if (currentIndex > index) {
                currentIndex--;
            }
        }
        if (currentId != null) {
            currentIndex = indexOf(currentId);
        }
    }

    public void play(@Nullable String audioId) throws Exception {
        awaitServiceReady();
        runOnMainBlocking(() -> playInternal(audioId));
    }

    private void playInternal(@Nullable String audioId) throws Exception {
        if (player == null) {
            throw new Exception("Player not ready");
        }

        // Resume current item (JS togglePlayPause calls play() with no audioId)
        if (audioId == null || audioId.isEmpty()) {
            if (player.isPlaying()) {
                return;
            }
            if (player.getMediaItemCount() > 0) {
                player.play();
                return;
            }
            if (currentIndex >= 0 && currentIndex < audioSources.size()) {
                playAtIndex(currentIndex);
                return;
            }
            if (!audioSources.isEmpty()) {
                playAtIndex(0);
                return;
            }
            throw new Exception("No audio sources available");
        }

        int index = indexOf(audioId);
        if (index < 0) {
            throw new Exception("Audio source not found: " + audioId);
        }

        MediaItem currentItem = player.getCurrentMediaItem();
        boolean sameItemLoaded = currentItem != null
            && audioId.equals(currentItem.mediaId)
            && player.getMediaItemCount() > 0;

        if (sameItemLoaded || (index == currentIndex && player.getMediaItemCount() > 0)) {
            currentIndex = index;
            if (!player.isPlaying()) {
                player.play();
            }
            return;
        }

        playAtIndex(index);
    }

    private void playAtIndex(int index) {
        if (player == null || index < 0 || index >= audioSources.size()) {
            return;
        }
        AudioSourceItem item = audioSources.get(index);
        Uri uri = Uri.parse(item.source);
        Log.d(TAG, "Playing index=" + index + " id=" + item.audioId + " uri=" + uri);

        MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setAlbumTitle(item.albumTitle)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);

        if (item.artworkSource != null && !item.artworkSource.isEmpty()) {
            metaBuilder.setArtworkUri(Uri.parse(item.artworkSource));
        }

        MediaItem mediaItem = new MediaItem.Builder()
            .setUri(uri)
            .setMediaId(item.audioId)
            .setMediaMetadata(metaBuilder.build())
            .build();

        currentIndex = index;
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    /** Match iOS: lock-screen next only notifies JS; JS loads/plays the next track. */
    void notifyPlayNext() {
        if (listener != null) {
            listener.onPlayNext();
        }
    }

    void notifyPlayPrevious() {
        if (listener != null) {
            listener.onPlayPrevious();
        }
    }

    void notifySeek(double timeInSeconds) {
        if (listener != null) {
            listener.onSeek(timeInSeconds);
        }
    }

    public void pause() {
        runOnMain(() -> {
            if (player != null) {
                player.pause();
            }
        });
    }

    public void stop() {
        runOnMain(() -> {
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            currentIndex = -1;
        });
    }

    public void seek(double timeInSeconds) {
        long positionMs = (long) (timeInSeconds * 1000.0);
        runOnMain(() -> {
            if (player != null) {
                player.seekTo(positionMs);
            }
            notifySeek(timeInSeconds);
        });
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (player != null) {
            float v = this.volume;
            runOnMain(() -> player.setVolume(v));
        }
    }

    public double getCurrentTime() {
        if (player == null) {
            return 0;
        }
        return runOnMainForResult(() -> player.getCurrentPosition() / 1000.0, 0.0);
    }

    public double getDuration() {
        if (player == null) {
            return 0;
        }
        return runOnMainForResult(() -> {
            long duration = player.getDuration();
            if (duration < 0) {
                return 0.0;
            }
            return duration / 1000.0;
        }, 0.0);
    }

    @Nullable
    public String getCurrentAudioId() {
        if (currentIndex >= 0 && currentIndex < audioSources.size()) {
            return audioSources.get(currentIndex).audioId;
        }
        return null;
    }

    public JSONObject getCurrentAudioJson() {
        JSONObject obj = new JSONObject();
        try {
            if (currentIndex >= 0 && currentIndex < audioSources.size()) {
                AudioSourceItem item = audioSources.get(currentIndex);
                obj.put("audioId", item.audioId);
                obj.put("source", item.source);
                obj.put("title", item.title);
                obj.put("artist", item.artist);
                obj.put("albumTitle", item.albumTitle);
                obj.put("artworkSource", item.artworkSource);
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    public void destroy() {
        stop();
        Intent intent = new Intent(appContext, AudioPlayerService.class);
        appContext.stopService(intent);
        audioSources.clear();
        currentIndex = -1;
    }

    private int indexOf(String audioId) {
        for (int i = 0; i < audioSources.size(); i++) {
            if (audioSources.get(i).audioId.equals(audioId)) {
                return i;
            }
        }
        return -1;
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private interface ResultSupplier<T> {
        T get() throws Exception;
    }

    private <T> T runOnMainForResult(ResultSupplier<T> supplier, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return supplier.get();
            } catch (Exception e) {
                Log.e(TAG, "Main-thread player read failed", e);
                return fallback;
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>(fallback);
        AtomicReference<Exception> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                result.set(supplier.get());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for main-thread player read");
                return fallback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
        if (error.get() != null) {
            Log.e(TAG, "Main-thread player read failed", error.get());
            return fallback;
        }
        return result.get();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void runOnMainBlocking(ThrowingRunnable r) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                r.run();
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new Exception("Timed out waiting for main-thread audio operation");
        }
        if (error.get() != null) {
            throw error.get();
        }
    }

    /**
     * Exposes next/previous to system media controls while keeping a single MediaItem loaded
     * (Wave windowing / JS owns the real playlist).
     */
    private class PlaylistForwardingPlayer extends ForwardingPlayer {
        PlaylistForwardingPlayer(Player player) {
            super(player);
        }

        @Override
        public void seekToNext() {
            notifyPlayNext();
        }

        @Override
        public void seekToNextMediaItem() {
            notifyPlayNext();
        }

        @Override
        public void seekToPrevious() {
            notifyPlayPrevious();
        }

        @Override
        public void seekToPreviousMediaItem() {
            notifyPlayPrevious();
        }

        @Override
        public void seekTo(long positionMs) {
            super.seekTo(positionMs);
            notifySeek(positionMs / 1000.0);
        }

        @Override
        public void seekTo(int mediaItemIndex, long positionMs) {
            super.seekTo(mediaItemIndex, positionMs);
            notifySeek(positionMs / 1000.0);
        }

        @Override
        public boolean isCommandAvailable(int command) {
            if (
                command == COMMAND_SEEK_TO_NEXT
                    || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    || command == COMMAND_SEEK_TO_PREVIOUS
                    || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ) {
                return true;
            }
            return super.isCommandAvailable(command);
        }

        @Override
        public Commands getAvailableCommands() {
            return super.getAvailableCommands()
                .buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build();
        }
    }

    public static List<AudioSourceItem> parseSources(JSONArray array) throws Exception {
        List<AudioSourceItem> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String audioId = o.optString("audioId", null);
            String source = o.optString("source", null);
            if (audioId == null || audioId.isEmpty() || source == null || source.isEmpty()) {
                throw new Exception("audioId and source are required for each audio source");
            }
            list.add(new AudioSourceItem(
                audioId,
                source,
                o.optString("title", ""),
                o.optString("artist", ""),
                o.optString("albumTitle", ""),
                o.optString("artworkSource", "")
            ));
        }
        return list;
    }
}
