package com.example.musicplayer;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlaybackService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "PlaybackService";
    private MediaPlayer mediaPlayer;
    private final IBinder binder = new LocalBinder();
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // For API 26+

    private List<Song> currentQueue = new ArrayList<>();
    private int currentIndex = -1;
    private Song currentSong;
    private boolean isPaused = false;
    private boolean isShuffle = false;
    private RepeatMode repeatMode = RepeatMode.NONE;

    private PlaybackStateListener playbackStateListener;
    private MediaSessionCompat mediaSession;
    private MediaNotificationManager mediaNotificationManager;
    private final BroadcastReceiver notificationActionReceiver = new NotificationActionReceiver();
    private boolean notificationReceiverRegistered = false;
    private BecomingNoisyReceiver becomingNoisyReceiver;
    private boolean becomingNoisyReceiverRegistered = false;

    public enum RepeatMode {
        NONE, ONE, ALL
    }

    public interface PlaybackStateListener {
        void onSongChanged(Song newSong);
        void onPlaybackStateChanged(boolean isPlaying);
        void onQueueUpdated(List<Song> queue, int newIndex);
        void onShuffleModeChanged(boolean isShuffleOn);
        void onRepeatModeChanged(RepeatMode newMode);
    }

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initializeMediaPlayer();
        initializeMediaSession();
        mediaNotificationManager = new MediaNotificationManager(this);
        registerNotificationActionReceiver();
        becomingNoisyReceiver = new BecomingNoisyReceiver(); // Initialize
        registerBecomingNoisyReceiver(); // Register
        Log.d(TAG, "PlaybackService Created, MediaSession Initialized, Notification and BecomingNoisy Receivers Registered");
    }

    private void initializeMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
        }
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setActive(true);
        updatePlaybackState(null); // Initial state is STOPPED

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                Log.d(TAG, "MediaSession.Callback: onPlay called");
                resumeSong();
            }

            @Override
            public void onPause() {
                super.onPause();
                Log.d(TAG, "MediaSession.Callback: onPause called");
                pauseSong();
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                Log.d(TAG, "MediaSession.Callback: onSkipToNext called");
                playNextSong();
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                Log.d(TAG, "MediaSession.Callback: onSkipToPrevious called");
                playPreviousSong();
            }

            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                Log.d(TAG, "MediaSession.Callback: onSeekTo " + pos + " called");
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                super.onStop();
                Log.d(TAG, "MediaSession.Callback: onStop called");
                stopSong();
            }
        });
    }

    public MediaSessionCompat.Token getMediaSessionToken() {
        if (mediaSession != null) {
            return mediaSession.getSessionToken();
        }
        return null;
    }

    private void registerNotificationActionReceiver() {
        if (!notificationReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(MediaNotificationManager.ACTION_PLAY_PAUSE);
            filter.addAction(MediaNotificationManager.ACTION_NEXT);
            filter.addAction(MediaNotificationManager.ACTION_PREVIOUS);
            filter.addAction(MediaNotificationManager.ACTION_STOP_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(notificationActionReceiver, filter);
            }
            notificationReceiverRegistered = true;
            Log.d(TAG, "NotificationActionReceiver registered.");
        }
    }

    private void unregisterNotificationActionReceiver() {
        if (notificationReceiverRegistered) {
            unregisterReceiver(notificationActionReceiver);
            notificationReceiverRegistered = false;
            Log.d(TAG, "NotificationActionReceiver unregistered.");
        }
    }

    private void registerBecomingNoisyReceiver() {
        if (becomingNoisyReceiver != null && !becomingNoisyReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(becomingNoisyReceiver, intentFilter);
            becomingNoisyReceiverRegistered = true;
            Log.d(TAG, "BecomingNoisyReceiver registered.");
        }
    }

    private void unregisterBecomingNoisyReceiver() {
        if (becomingNoisyReceiver != null && becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver);
            becomingNoisyReceiverRegistered = false;
            Log.d(TAG, "BecomingNoisyReceiver unregistered.");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void setPlaybackStateListener(PlaybackStateListener listener) {
        this.playbackStateListener = listener;
    }

    public void setQueue(List<Song> songs, int index) {
        this.currentQueue.clear();
        if (songs != null) {
            this.currentQueue.addAll(songs);
        }
        this.currentIndex = index;
        if (playbackStateListener != null) {
            playbackStateListener.onQueueUpdated(this.currentQueue, this.currentIndex);
        }
        if (this.currentIndex >= 0 && this.currentIndex < this.currentQueue.size()) {
            playSongAtIndex(this.currentIndex);
        } else {
            Log.w(TAG, "setQueue: Invalid index or empty queue. Playback not started.");
            if (currentSong != null || isPlaying()) {
                 stopSong();
            }
        }
    }

    public void playSongAtIndex(int index) {
        if (index < 0 || index >= currentQueue.size()) {
            Log.e(TAG, "Invalid index for playSongAtIndex: " + index);
            if (repeatMode == RepeatMode.ALL && !currentQueue.isEmpty()) {
                currentIndex = 0; 
            } else {
                stopSong(); 
                return;
            }
        } else {
            currentIndex = index;
        }

        Song songToPlay = currentQueue.get(currentIndex);
        this.currentSong = songToPlay;
        isPaused = false;
        Log.d(TAG, "Attempting to play from queue: " + songToPlay.getTitle() + " at index " + currentIndex);

        if (!requestAudioFocus()) {
            Log.e(TAG, "Could not obtain audio focus for " + songToPlay.getTitle());
            Toast.makeText(this, "Could not obtain audio focus", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (mediaPlayer == null) {
                initializeMediaPlayer();
            }
            mediaPlayer.reset();
            mediaPlayer.setDataSource(songToPlay.getPath());
            mediaPlayer.prepareAsync();
            Log.d(TAG, "Preparing song: " + songToPlay.getTitle());

            updateMediaMetadata(songToPlay);
            mediaNotificationManager.buildNotificationAndStartForeground(songToPlay, false, getMediaSessionToken());

            if (playbackStateListener != null) {
                playbackStateListener.onSongChanged(songToPlay);
            }
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error setting data source or player in wrong state for " + songToPlay.getTitle() + ": " + e.getMessage());
            Toast.makeText(this, "Error playing song", Toast.LENGTH_SHORT).show();
            abandonAudioFocus();
        }
    }

    public void playSong(Song song) {
        if (song == null) {
            Log.e(TAG, "Cannot play a null song.");
            return;
        }
        setQueue(new ArrayList<>(List.of(song)), 0);
    }

    public void playNextSong() {
        if (currentQueue.isEmpty()) return;

        if (repeatMode == RepeatMode.ONE && !isPaused) {
            playSongAtIndex(currentIndex);
            return;
        }

        int nextIndex = currentIndex + 1;
        if (isShuffle) {
            if (currentQueue.size() > 1) {
                int newIndex;
                do {
                    newIndex = new Random().nextInt(currentQueue.size());
                } while (newIndex == currentIndex);
                nextIndex = newIndex;
            } else {
                nextIndex = currentIndex;
            }
        } else {
            if (nextIndex >= currentQueue.size()) {
                if (repeatMode == RepeatMode.ALL) {
                    nextIndex = 0;
                } else {
                    Log.d(TAG, "End of queue reached.");
                    stopSong();
                    return;
                }
            }
        }
        playSongAtIndex(nextIndex);
    }

    public void playPreviousSong() {
        if (currentQueue.isEmpty()) return;

        int prevIndex = currentIndex - 1;
        if (isShuffle) {
            if (currentQueue.size() > 1) {
                int newIndex;
                do {
                    newIndex = new Random().nextInt(currentQueue.size());
                } while (newIndex == currentIndex);
                prevIndex = newIndex;
            } else {
                prevIndex = currentIndex;
            }
        } else {
            if (prevIndex < 0) {
                if (repeatMode == RepeatMode.ALL && !currentQueue.isEmpty()) {
                    prevIndex = currentQueue.size() - 1;
                } else {
                    if (mediaPlayer != null && getCurrentPosition() > 3000) {
                         seekTo(0);
                         if (!mediaPlayer.isPlaying()) resumeSong();
                         return;
                    }
                    Log.d(TAG, "Start of queue reached.");
                    return; 
                }
            }
        }
        playSongAtIndex(prevIndex);
    }

    public void pauseSong() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.pause();
                isPaused = true;
                abandonAudioFocus();
                Log.d(TAG, "Song paused: " + (currentSong != null ? currentSong.getTitle() : "Unknown"));
                updatePlaybackState(currentSong);
                if (playbackStateListener != null) playbackStateListener.onPlaybackStateChanged(false);
                if (currentSong != null) {
                     mediaNotificationManager.buildNotificationAndStartForeground(currentSong, false, getMediaSessionToken());
                     stopForeground(false);
                } else {
                    stopForeground(true);
                    mediaNotificationManager.hideNotification();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing media player: " + e.getMessage());
            }
        }
    }

    public void resumeSong() {
        if (mediaPlayer != null && isPaused && currentSong != null) {
            if (!requestAudioFocus()) {
                Log.e(TAG, "Could not obtain audio focus for resume.");
                Toast.makeText(this, "Could not resume: audio focus issue", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                mediaPlayer.start();
                isPaused = false;
                Log.d(TAG, "Song resumed: " + currentSong.getTitle());
                updatePlaybackState(currentSong);
                if (playbackStateListener != null) playbackStateListener.onPlaybackStateChanged(true);
                mediaNotificationManager.buildNotificationAndStartForeground(currentSong, true, getMediaSessionToken());
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to resume song, MediaPlayer in wrong state: " + e.getMessage());
                playSongAtIndex(currentIndex);
            }
        } else if (currentSong == null && !currentQueue.isEmpty() && currentIndex != -1) {
            playSongAtIndex(currentIndex);
        } else {
            Log.d(TAG, "Cannot resume: mediaplayer is null, not paused, or no current song.");
            if (currentSong == null) {
                stopSong();
            }
        }
    }

    public void stopSong() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying() || isPaused) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.reset();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping/resetting media player: " + e.getMessage());
                    mediaPlayer.reset();
                }
            } else {
                 mediaPlayer.reset();
            }
            isPaused = false;
            abandonAudioFocus();
            Log.d(TAG, "Song stopped and media player reset.");
            updatePlaybackState(null);
            currentSong = null;
            if (playbackStateListener != null) playbackStateListener.onPlaybackStateChanged(false);

            stopForeground(true);
            mediaNotificationManager.hideNotification();
        }
    }

    public Song getCurrentSong() {
        if (currentIndex != -1 && currentIndex < currentQueue.size()) {
            return currentQueue.get(currentIndex);
        }
        return currentSong;
    }

    public List<Song> getCurrentQueue() {
        return currentQueue;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public boolean toggleShuffle() {
        isShuffle = !isShuffle;
        Log.d(TAG, "Shuffle mode toggled to: " + isShuffle);
        if (playbackStateListener != null) {
            playbackStateListener.onShuffleModeChanged(isShuffle);
        }
        return isShuffle;
    }

    public boolean isShuffleEnabled() {
        return isShuffle;
    }

    public RepeatMode toggleRepeatMode() {
        switch (repeatMode) {
            case NONE: repeatMode = RepeatMode.ALL; break;
            case ALL: repeatMode = RepeatMode.ONE; break;
            case ONE: repeatMode = RepeatMode.NONE; break;
        }
        Log.d(TAG, "Repeat mode toggled to: " + repeatMode);
        if (playbackStateListener != null) {
            playbackStateListener.onRepeatModeChanged(repeatMode);
        }
        return repeatMode;
    }

    public RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null && (mediaPlayer.isPlaying() || isPaused)) {
            try {
                 return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                Log.e(TAG, "getCurrentPosition called in invalid state: " + e.getMessage());
            }
        }
        return 0;
    }

    public int getDuration() {
        if (mediaPlayer != null && (mediaPlayer.isPlaying() || isPaused)) {
             try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                Log.e(TAG, "getDuration called in invalid state: " + e.getMessage());
            }
        }
        return 0;
    }

    public void seekTo(int position) {
        if (mediaPlayer != null && (mediaPlayer.isPlaying() || isPaused)) {
            try {
                mediaPlayer.seekTo(position);
            } catch (IllegalStateException e) {
                Log.e(TAG, "seekTo called in invalid state: " + e.getMessage());
            }
        }
    }

    private void updatePlaybackState(Song song) {
        long position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
        int state = isPaused ? PlaybackStateCompat.STATE_PAUSED :
                    (isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_STOPPED);

        if (mediaPlayer != null && (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED)) {
            try {
                position = mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaPlayer not in a valid state to get current position for PlaybackStateCompat.");
                position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
            }
        }

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO |
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());

        if (state == PlaybackStateCompat.STATE_STOPPED) {
             if (this.currentSong == null) { 
                stopForeground(true);
                mediaNotificationManager.hideNotification();
             } else if (song != null) { 
                mediaNotificationManager.buildNotificationAndStartForeground(song, false, getMediaSessionToken());
                stopForeground(true); 
             }
        } else if (song != null) { 
            mediaNotificationManager.buildNotificationAndStartForeground(song, state == PlaybackStateCompat.STATE_PLAYING, getMediaSessionToken());
            if (state != PlaybackStateCompat.STATE_PLAYING) {
                 stopForeground(false); 
            }
        }
        Log.d(TAG, "PlaybackState updated. State: " + state + ", Position: " + position + ", Song: " + (song != null ? song.getTitle() : "null"));
    }

    private void updateMediaMetadata(Song song) {
        if (song == null) {
            if (mediaSession.getController().getMetadata() != null) {
                 mediaSession.setMetadata(null);
                 Log.d(TAG, "MediaMetadata cleared.");
            }
            return;
        }
        MediaMetadataCompat currentMetadata = mediaSession.getController().getMetadata();
        if (currentMetadata != null && song.getTitle().equals(currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))) {
            if (currentMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) <= 0 && song.getDuration() > 0) {
                 // Continue to update if duration was missing
            } else {
                // return; // Metadata seems up-to-date for this song
            }
        }

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.getAlbum())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.getDuration());

        Glide.with(this)
                .asBitmap()
                .load(song.getAlbumArtUri())
                .error(R.drawable.ic_default_album_art)
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, resource);
                        mediaSession.setMetadata(metadataBuilder.build());
                        Log.d(TAG, "MediaMetadata (async) updated with album art for: " + song.getTitle());
                        if (PlaybackService.this.currentSong != null && PlaybackService.this.currentSong.getId() == song.getId()) {
                             updatePlaybackState(song);
                        }
                    }
                     @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        super.onLoadFailed(errorDrawable);
                        Bitmap defaultArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_default_album_art);
                        if (defaultArt != null) {
                            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, defaultArt);
                        }
                        mediaSession.setMetadata(metadataBuilder.build());
                        Log.d(TAG, "MediaMetadata (async) updated with default album art for: " + song.getTitle());
                        if (PlaybackService.this.currentSong != null && PlaybackService.this.currentSong.getId() == song.getId()) {
                             updatePlaybackState(song);
                        }
                    }
                });
        mediaSession.setMetadata(metadataBuilder.build());
        Log.d(TAG, "MediaMetadata (initial sync) updated for: " + song.getTitle());
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.d(TAG, "MediaPlayer prepared, starting playback for: " + (currentSong != null ? currentSong.getTitle() : "Unknown"));
        mp.start();
        isPaused = false;
        updatePlaybackState(currentSong);
        if (playbackStateListener != null) {
            playbackStateListener.onPlaybackStateChanged(true);
            if (currentSong != null) playbackStateListener.onSongChanged(currentSong);
        }
        mediaNotificationManager.buildNotificationAndStartForeground(currentSong, true, getMediaSessionToken());
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Song completed: " + (currentSong != null ? currentSong.getTitle() : "Unknown"));
        Song completedSong = currentSong;
        isPaused = false; 
        
        if (repeatMode == RepeatMode.ONE) {
            playSongAtIndex(currentIndex);
        } else {
            playNextSong(); 
        }
        if (this.currentSong == null && !isPlaying() && !isPaused) { 
             updatePlaybackState(completedSong); 
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer Error: What=" + what + ", Extra=" + extra + " for song: " + (currentSong != null ? currentSong.getTitle() : "Unknown"));
        Toast.makeText(this, "Error playing track. Skipping.", Toast.LENGTH_LONG).show();
        isPaused = false;
        try {
            if (mp != null) mp.reset();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error resetting media player on error: " + e.getMessage());
        }
        abandonAudioFocus(); 
        
        Song problematicSong = currentSong;
        currentSong = null; 
        
        updatePlaybackState(null); 
        
        if (playbackStateListener != null) {
            playbackStateListener.onPlaybackStateChanged(false);
        }
        
        if (currentQueue != null && !currentQueue.isEmpty() && problematicSong != null) {
            int problematicIndex = currentQueue.indexOf(problematicSong);
            if (problematicIndex != -1 && problematicIndex == currentIndex) {
                 Log.d(TAG, "Attempting to play next song after error.");
                 if (repeatMode == RepeatMode.ONE) {
                     setRepeatMode(RepeatMode.NONE); 
                     playNextSong();
                     setRepeatMode(RepeatMode.ONE); 
                 } else if (currentQueue.size() == 1) {
                     Log.d(TAG, "Only one song in queue, which is problematic. Stopping.");
                     stopSong(); 
                 }
                 else {
                     playNextSong();
                 }
            } else {
                Log.d(TAG, "Error for a song not currently indexed or queue empty. Stopping.");
                stopSong();
            }
        } else {
             Log.d(TAG, "No queue or problematic song context for error. Stopping.");
            stopSong();
        }
        return true; 
    }
    
    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "AUDIOFOCUS_GAIN");
                if (mediaPlayer == null) {
                    initializeMediaPlayer(); 
                }
                mediaPlayer.setVolume(1.0f, 1.0f);
                if (isPaused && currentSong != null) {
                    // Optional: resumeSong(); 
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "AUDIOFOCUS_LOSS (e.g., incoming call)");
                if (mediaPlayer != null && (mediaPlayer.isPlaying() || isPaused)) {
                    try {
                        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                        mediaPlayer.reset(); 
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error stopping/resetting media player on AUDIOFOCUS_LOSS: " + e.getMessage());
                        if (mediaPlayer != null) mediaPlayer.reset();
                    }
                    isPaused = false; 
                    currentSong = null;
                    updatePlaybackState(null); 
                    if (playbackStateListener != null) playbackStateListener.onPlaybackStateChanged(false);
                    Log.d(TAG, "Playback stopped due to AUDIOFOCUS_LOSS.");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    try {
                        mediaPlayer.pause();
                        isPaused = true;
                        updatePlaybackState(currentSong); 
                        if (playbackStateListener != null) playbackStateListener.onPlaybackStateChanged(false);
                        Log.d(TAG, "Playback paused due to AUDIOFOCUS_LOSS_TRANSIENT.");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error pausing media player on AUDIOFOCUS_LOSS_TRANSIENT: " + e.getMessage());
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    try {
                        mediaPlayer.setVolume(0.3f, 0.3f); 
                        Log.d(TAG, "Volume ducked due to AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK.");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error ducking volume on AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: " + e.getMessage());
                    }
                }
                break;
        }
    }

    // Inner class for handling ACTION_AUDIO_BECOMING_NOISY
    private class BecomingNoisyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "ACTION_AUDIO_BECOMING_NOISY received");
                if (isPlaying()) {
                    pauseSong();
                    Log.d(TAG, "Playback paused due to audio becoming noisy.");
                }
            }
        }
    }
    
    // Inner class for handling Notification Actions
    private class NotificationActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "NotificationActionReceiver received action: " + action);
            switch (action) {
                case MediaNotificationManager.ACTION_PLAY_PAUSE:
                    if (isPlaying()) {
                        pauseSong();
                    } else {
                        resumeSong();
                    }
                    break;
                case MediaNotificationManager.ACTION_NEXT:
                    playNextSong();
                    break;
                case MediaNotificationManager.ACTION_PREVIOUS:
                    playPreviousSong();
                    break;
                case MediaNotificationManager.ACTION_STOP_SERVICE:
                    stopSong();
                    stopSelf(); 
                    break;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        abandonAudioFocus();
        unregisterNotificationActionReceiver();
        unregisterBecomingNoisyReceiver(); 
        stopForeground(true);
        if (mediaNotificationManager != null) {
            mediaNotificationManager.hideNotification();
        }
        Log.d(TAG, "PlaybackService Destroyed");
    }
}
