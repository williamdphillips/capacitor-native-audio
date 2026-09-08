package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Capacitor AudioPlayer plugin — Android implementation aligned with the iOS
 * playlist API (createMultiple / setAudioSources / play by audioId).
 */
@CapacitorPlugin(name = "AudioPlayer")
public class AudioPlayerPlugin extends Plugin implements PlaylistAudioManager.Listener {

    private static final String TAG = "AudioPlayerPlugin";

    private PlaylistAudioManager audioManager;

    @Override
    public void load() {
        super.load();
        audioManager = PlaylistAudioManager.getInstance(getContext());
        audioManager.setListener(this);
        Log.i(TAG, "Playlist AudioPlayer loaded");
    }

    @PluginMethod
    public void createMultiple(PluginCall call) {
        try {
            JSArray sources = call.getArray("audioSources");
            if (sources == null) {
                call.reject("audioSources parameter is required");
                return;
            }
            audioManager.createMultiple(PlaylistAudioManager.parseSources(sources));
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception ex) {
            Log.e(TAG, "createMultiple failed", ex);
            call.reject("There was an issue creating multiple audio sources: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void setAudioSources(PluginCall call) {
        try {
            JSArray sources = call.getArray("audioSources");
            if (sources == null) {
                call.reject("audioSources parameter is required");
                return;
            }
            audioManager.setAudioSources(PlaylistAudioManager.parseSources(sources));
            call.resolve();
        } catch (Exception ex) {
            Log.e(TAG, "setAudioSources failed", ex);
            call.reject("There was an issue setting audio sources: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void removeAudioSources(PluginCall call) {
        try {
            JSArray ids = call.getArray("audioIds");
            List<String> audioIds = new ArrayList<>();
            if (ids != null) {
                JSONArray arr = ids;
                for (int i = 0; i < arr.length(); i++) {
                    audioIds.add(arr.getString(i));
                }
            }
            audioManager.removeAudioSources(audioIds);
            call.resolve();
        } catch (Exception ex) {
            call.reject("There was an issue removing audio sources: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void play(PluginCall call) {
        try {
            String audioId = call.getString("audioId");
            audioManager.play(audioId);
            call.resolve();
        } catch (Exception ex) {
            Log.e(TAG, "play failed", ex);
            call.reject("There was an issue playing the audio: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void pause(PluginCall call) {
        audioManager.pause();
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        audioManager.stop();
        call.resolve();
    }

    @PluginMethod
    public void seek(PluginCall call) {
        Double timeInSeconds = call.getDouble("timeInSeconds");
        if (timeInSeconds == null) {
            call.reject("timeInSeconds parameter is required");
            return;
        }
        audioManager.seek(timeInSeconds);
        call.resolve();
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        Float volume = call.getFloat("volume");
        if (volume == null) {
            call.reject("Volume parameter is missing or invalid");
            return;
        }
        if (volume < 0f || volume > 1f) {
            call.reject("Volume must be between 0.0 and 1.0");
            return;
        }
        audioManager.setVolume(volume);
        call.resolve();
    }

    @PluginMethod
    public void getCurrentTime(PluginCall call) {
        JSObject result = new JSObject();
        result.put("currentTime", audioManager.getCurrentTime());
        call.resolve(result);
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        JSObject result = new JSObject();
        result.put("duration", audioManager.getDuration());
        call.resolve(result);
    }

    @PluginMethod
    public void getCurrentAudio(PluginCall call) {
        try {
            call.resolve(new JSObject(audioManager.getCurrentAudioJson().toString()));
        } catch (Exception ex) {
            call.resolve(new JSObject());
        }
    }

    @PluginMethod
    public void next(PluginCall call) {
        // Wave UI primarily drives next via JS; emit for lock-screen parity later.
        onPlayNext();
        call.resolve();
    }

    @PluginMethod
    public void previous(PluginCall call) {
        onPlayPrevious();
        call.resolve();
    }

    @PluginMethod
    public void showAirPlayMenu(PluginCall call) {
        // AirPlay is iOS-only
        call.resolve();
    }

    // --- Legacy MediaGrid methods (no-op / soft reject so older callers don't crash hard) ---

    @PluginMethod
    public void create(PluginCall call) {
        call.reject("Use createMultiple / setAudioSources on Android (iOS playlist API).");
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void changeAudioSource(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void changeMetadata(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void getDurationSeconds(PluginCall call) {
        getDuration(call);
    }

    @PluginMethod
    public void getCurrentTimeSeconds(PluginCall call) {
        getCurrentTime(call);
    }

    @PluginMethod
    public void isPlaying(PluginCall call) {
        JSObject result = new JSObject();
        result.put("isPlaying", false);
        call.resolve(result);
    }

    @PluginMethod
    public void destroy(PluginCall call) {
        audioManager.destroy();
        audioManager = PlaylistAudioManager.getInstance(getContext());
        audioManager.setListener(this);
        call.resolve();
    }

    @PluginMethod
    public void onAudioReady(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void onAudioEnd(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void onPlaybackStatusChange(PluginCall call) {
        call.resolve();
    }

    @Override
    public void onPlaybackStatusChange(boolean isPlaying) {
        JSObject data = new JSObject();
        data.put("isPlaying", isPlaying);
        String audioId = audioManager.getCurrentAudioId();
        if (audioId != null) {
            data.put("audioId", audioId);
        }
        notifyListeners("onPlaybackStatusChange", data);
    }

    @Override
    public void onAudioEnd() {
        JSObject data = new JSObject();
        String audioId = audioManager.getCurrentAudioId();
        if (audioId != null) {
            data.put("audioId", audioId);
        }
        notifyListeners("onAudioEnd", data);
    }

    @Override
    public void onPlayNext() {
        notifyListeners("onPlayNext", new JSObject());
    }

    @Override
    public void onPlayPrevious() {
        notifyListeners("onPlayPrevious", new JSObject());
    }

    @Override
    public void onPlaybackError(String audioId, String error) {
        JSObject data = new JSObject();
        data.put("audioId", audioId != null ? audioId : "");
        data.put("error", error != null ? error : "Unknown error");
        notifyListeners("onPlaybackError", data);
    }

    @Override
    public void onSeek(double time) {
        JSObject data = new JSObject();
        data.put("time", time);
        notifyListeners("onSeek", data);
    }
}
