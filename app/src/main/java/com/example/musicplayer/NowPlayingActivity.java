package com.example.musicplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class NowPlayingActivity extends AppCompatActivity implements PlaybackService.PlaybackStateListener {

    private static final String TAG = "NowPlayingActivity";

    private ImageView imageViewAlbumArt;
    private TextView textViewTitle, textViewArtist, textViewCurrentTime, textViewTotalDuration;
    private SeekBar seekBar;
    private ImageButton buttonPlayPause, buttonNext, buttonPrevious, buttonShuffle, buttonRepeat;

    private PlaybackService playbackService;
    private boolean isServiceBound = false;
    private Intent serviceIntent;

    private Handler updateSeekBarHandler = new Handler();
    private Runnable updateSeekBarRunnable;


    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.LocalBinder binder = (PlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            isServiceBound = true;
            playbackService.setPlaybackStateListener(NowPlayingActivity.this); // Register listener
            Log.d(TAG, "PlaybackService connected to NowPlayingActivity and listener registered.");
            updateUIWithCurrentSong(); // Initial UI setup
            updateShuffleRepeatButtonStates(); // Set initial button states
            setupPlaybackControls();   // Setup button listeners
            startUpdatingSeekBar();    // Start seekbar updates
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (playbackService != null) {
                playbackService.setPlaybackStateListener(null); // Unregister listener
            }
            isServiceBound = false;
            playbackService = null;
            Log.d(TAG, "PlaybackService disconnected from NowPlayingActivity and listener unregistered.");
            stopUpdatingSeekBar();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        // Initialize UI elements
        imageViewAlbumArt = findViewById(R.id.imageViewNowPlayingAlbumArt);
        textViewTitle = findViewById(R.id.textViewNowPlayingTitle);
        textViewArtist = findViewById(R.id.textViewNowPlayingArtist);
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime);
        textViewTotalDuration = findViewById(R.id.textViewTotalDuration);
        seekBar = findViewById(R.id.seekBarNowPlaying);
        buttonPlayPause = findViewById(R.id.buttonPlayPause);
        buttonNext = findViewById(R.id.buttonNext);
        buttonPrevious = findViewById(R.id.buttonPrevious);
        buttonShuffle = findViewById(R.id.buttonShuffle); // Initialize if you will use them
        buttonRepeat = findViewById(R.id.buttonRepeat);   // Initialize if you will use them

        serviceIntent = new Intent(this, PlaybackService.class);
        // The service should already be started by MainActivity, but ensure it's running
        // startService(serviceIntent); // This is okay to call even if service is running
        // bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        Log.d(TAG, "NowPlayingActivity Created");
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to the service if it's not already bound
        if (!isServiceBound) {
            // Check if the service is already running, if not, start it.
            // This is important if NowPlayingActivity can be started independently
            // or if MainActivity didn't start it for some reason.
            startService(serviceIntent);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "Attempting to bind PlaybackService from NowPlayingActivity onStart");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unbind from the service if it's bound.
        // Do not stop the service here as it might be playing music in the background.
        // if (isServiceBound) {
        //     unbindService(serviceConnection);
        //     isServiceBound = false;
        //     Log.d(TAG, "PlaybackService unbound from NowPlayingActivity onStop");
        // }
        // stopUpdatingSeekBar(); // Stop updates when activity is not visible
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
            Log.d(TAG, "PlaybackService unbound from NowPlayingActivity onDestroy");
        }
        stopUpdatingSeekBar();
    }


    private void updateUIWithCurrentSong() {
        if (isServiceBound && playbackService != null) {
            Song currentSong = playbackService.getCurrentSong(); // Assumes PlaybackService has this method
            if (currentSong != null) {
                textViewTitle.setText(currentSong.getTitle());
                textViewArtist.setText(currentSong.getArtist());

                Glide.with(this)
                        .load(Uri.parse(currentSong.getAlbumArtUri()))
                        .placeholder(R.drawable.ic_default_album_art)
                        .error(R.drawable.ic_default_album_art)
                        .into(imageViewAlbumArt);
                
                updatePlayPauseButton();
                updateDurationUI(playbackService.getDuration());
                seekBar.setMax(playbackService.getDuration());

            } else {
                Log.d(TAG, "No current song in service to update UI.");
                // Optionally, set default texts or clear views
                textViewTitle.setText("No Song Playing");
                textViewArtist.setText("");
                imageViewAlbumArt.setImageResource(R.drawable.ic_default_album_art);
                textViewCurrentTime.setText("0:00");
                textViewTotalDuration.setText("0:00");
                seekBar.setProgress(0);
                seekBar.setMax(100); // Default max
                buttonPlayPause.setImageResource(R.drawable.ic_play_arrow);
            }
        }
    }
    
    private void updatePlayPauseButton() {
        if (isServiceBound && playbackService != null) {
            if (playbackService.isPlaying()) {
                buttonPlayPause.setImageResource(R.drawable.ic_pause);
            } else {
                buttonPlayPause.setImageResource(R.drawable.ic_play_arrow);
            }
        }
    }

    private void updateDurationUI(int durationMs) {
        textViewTotalDuration.setText(formatMillis(durationMs));
        seekBar.setMax(durationMs > 0 ? durationMs : 100); // Ensure max is not 0
    }


    private void setupPlaybackControls() {
        buttonPlayPause.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                if (playbackService.isPlaying()) {
                    playbackService.pauseSong();
                } else {
                    // If no song is loaded or if it was stopped, play the current (or first)
                    if (playbackService.getCurrentSong() == null) {
                        // Potentially load a default song or queue from MainActivity's perspective
                        // This case needs more robust handling based on app flow
                        Toast.makeText(this, "No song selected to play.", Toast.LENGTH_SHORT).show();
                    } else {
                        playbackService.resumeSong(); // Or playSong if it handles start implicitly
                    }
                }
                updatePlayPauseButton();
            }
        });

        buttonNext.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.playNextSong(); // Assumes PlaybackService has this method
                // UI should update via a callback or by re-fetching current song info
            }
        });

        buttonPrevious.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.playPreviousSong(); // Assumes PlaybackService has this method
                // UI should update via a callback
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int userSelectedPosition = 0;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    userSelectedPosition = progress;
                    textViewCurrentTime.setText(formatMillis(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopUpdatingSeekBar(); // Stop handler while user is interacting
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isServiceBound && playbackService != null) {
                    playbackService.seekTo(userSelectedPosition);
                }
                startUpdatingSeekBar(); // Restart handler
            }
        });
    }

    private void startUpdatingSeekBar() {
        if (updateSeekBarRunnable == null) {
            updateSeekBarRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isServiceBound && playbackService != null && playbackService.isPlaying()) {
                        int currentPosition = playbackService.getCurrentPosition();
                        seekBar.setProgress(currentPosition);
                        textViewCurrentTime.setText(formatMillis(currentPosition));
                        
                        // Update total duration if it changed (e.g. new song loaded)
                        if(seekBar.getMax() != playbackService.getDuration()){
                             updateDurationUI(playbackService.getDuration());
                        }
                    }
                    updateSeekBarHandler.postDelayed(this, 500); // Update every 500ms
                }
            };
        }
        updateSeekBarHandler.post(updateSeekBarRunnable);
    }

    private void stopUpdatingSeekBar() {
        if (updateSeekBarRunnable != null) {
            updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable);
        }
    }

    private String formatMillis(long millis) {
        return String.format(Locale.getDefault(), "%01d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(millis),
                TimeUnit.MILLISECONDS.toSeconds(millis) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        );
    }

    // Method to be called by PlaybackService listener when song changes
    // --- PlaybackService.PlaybackStateListener Implementation ---
    @Override
    public void onSongChanged(Song newSong) {
        Log.d(TAG, "Listener: onSongChanged called with " + (newSong != null ? newSong.getTitle() : "null song"));
        runOnUiThread(() -> {
            updateUIWithCurrentSong(); // This will fetch the latest song from service
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "Listener: onPlaybackStateChanged called with isPlaying: " + isPlaying);
        runOnUiThread(() -> {
            updatePlayPauseButton();
            if (isPlaying) {
                startUpdatingSeekBar();
            } else {
                // When paused, ensure the seekbar reflects the current position accurately
                // without continuous polling if the handler is stopped for paused state.
                if (isServiceBound && playbackService != null) {
                    seekBar.setProgress(playbackService.getCurrentPosition());
                    textViewCurrentTime.setText(formatMillis(playbackService.getCurrentPosition()));
                }
                // Depending on startUpdatingSeekBar logic, you might not need to explicitly stop it
                // if it already checks isPlaying().
            }
        });
    }

    @Override
    public void onQueueUpdated(List<Song> queue, int newIndex) {
        Log.d(TAG, "Listener: onQueueUpdated called. New index: " + newIndex);
        runOnUiThread(() -> {
            updateUIWithCurrentSong();
        });
    }

    @Override
    public void onShuffleModeChanged(boolean isShuffleOn) {
        Log.d(TAG, "Listener: onShuffleModeChanged called with isShuffleOn: " + isShuffleOn);
        runOnUiThread(this::updateShuffleButtonVisual);
    }

    @Override
    public void onRepeatModeChanged(PlaybackService.RepeatMode newMode) {
        Log.d(TAG, "Listener: onRepeatModeChanged called with newMode: " + newMode);
        runOnUiThread(this::updateRepeatButtonVisual);
    }
    // --- End of PlaybackStateListener Implementation ---

    private void updateShuffleRepeatButtonStates() {
        if (isServiceBound && playbackService != null) {
            updateShuffleButtonVisual();
            updateRepeatButtonVisual();
        }
    }

    private void updateShuffleButtonVisual() {
        if (isServiceBound && playbackService != null) {
            boolean isShuffle = playbackService.isShuffleEnabled();
            buttonShuffle.setAlpha(isShuffle ? 1.0f : 0.5f); // Example: full opacity if on, half if off
            // Or change tint:
            // buttonShuffle.setColorFilter(isShuffle ? ContextCompat.getColor(this, R.color.your_accent_color) : Color.GRAY, PorterDuff.Mode.SRC_IN);
            Log.d(TAG, "Shuffle button visual updated, isShuffle: " + isShuffle);
        }
    }

    private void updateRepeatButtonVisual() {
        if (isServiceBound && playbackService != null) {
            PlaybackService.RepeatMode mode = playbackService.getRepeatMode();
            switch (mode) {
                case NONE:
                    buttonRepeat.setImageResource(R.drawable.ic_repeat);
                    buttonRepeat.setAlpha(0.5f);
                    break;
                case ALL:
                    buttonRepeat.setImageResource(R.drawable.ic_repeat);
                    buttonRepeat.setAlpha(1.0f);
                    break;
                case ONE:
                    buttonRepeat.setImageResource(R.drawable.ic_repeat_one);
                    buttonRepeat.setAlpha(1.0f);
                    break;
            }
            Log.d(TAG, "Repeat button visual updated, mode: " + mode);
        }
    }

    private void setupPlaybackControls() { // Modified to include shuffle/repeat
        buttonPlayPause.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                if (playbackService.isPlaying()) {
                    playbackService.pauseSong();
                } else {
                    if (playbackService.getCurrentSong() == null) {
                        Toast.makeText(this, "No song selected to play.", Toast.LENGTH_SHORT).show();
                    } else {
                        playbackService.resumeSong();
                    }
                }
                // updatePlayPauseButton(); // Listener will handle this
            }
        });

        buttonNext.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.playNextSong();
            }
        });

        buttonPrevious.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.playPreviousSong();
            }
        });

        buttonShuffle.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.toggleShuffle();
                // updateShuffleButtonVisual(); // Listener will handle this
            }
        });

        buttonRepeat.setOnClickListener(v -> {
            if (isServiceBound && playbackService != null) {
                playbackService.toggleRepeatMode();
                // updateRepeatButtonVisual(); // Listener will handle this
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int userSelectedPosition = 0;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    userSelectedPosition = progress;
                    textViewCurrentTime.setText(formatMillis(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopUpdatingSeekBar(); // Stop handler while user is interacting
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isServiceBound && playbackService != null) {
                    playbackService.seekTo(userSelectedPosition);
                }
                startUpdatingSeekBar(); // Restart handler
            }
        });
    }
}
