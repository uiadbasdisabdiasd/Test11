package com.example.musicplayer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import android.content.pm.PackageManager; // Required for PackageManager.PERMISSION_GRANTED
import android.os.Build; // Required for Build.VERSION.SDK_INT and Build.VERSION_CODES.TIRAMISU
import androidx.core.app.ActivityCompat; // Required for ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.provider.Settings; // Required for opening app settings
import android.net.Uri; // Required for opening app settings
import com.google.android.material.snackbar.Snackbar; // Required for Snackbar
import android.view.View; // Required for Snackbar anchor view


import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SongAdapter.OnSongClickListener {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String TAG = "MainActivity";
    private static final int POST_NOTIFICATION_PERMISSION_REQUEST_CODE = 102; // Different from media permissions

    private RecyclerView recyclerViewSongs;
    private SongAdapter songAdapter;
    private List<Song> songList = new ArrayList<>();

    private PlaybackService playbackService;
    private boolean isServiceBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.LocalBinder binder = (PlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            isServiceBound = true;
            Log.d(TAG, "PlaybackService connected");
            // If there's a song list already loaded, you might want to pass it to the service
            // or if a song was clicked before service was bound, play it now.
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
            playbackService = null;
            Log.d(TAG, "PlaybackService disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerViewSongs = findViewById(R.id.recyclerViewSongs);
        recyclerViewSongs.setLayoutManager(new LinearLayoutManager(this));
        songAdapter = new SongAdapter(this, songList, this);
        recyclerViewSongs.setAdapter(songAdapter);


        if (checkAndRequestPermissions()) { // Handles READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE
            // Permissions already granted for media, proceed with media scanning
            scanMusic();
        }
        // After setting up media scanning, also check for notification permission
        checkAndRequestPostNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, PlaybackService.class);
        startService(intent); // Start the service to keep it running
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "Attempting to bind PlaybackService");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
            Log.d(TAG, "PlaybackService unbound");
        }
        // Consider stopping the service if no music is playing and app is not in foreground
        // if (playbackService != null && !playbackService.isPlaying()) {
        // stopService(new Intent(this, PlaybackService.class));
        // }
    }


    @Override
    public void onSongClick(Song song, int position) {
        if (isServiceBound && playbackService != null) {
            Log.d(TAG, "Song clicked: " + song.getTitle() + " at position " + position);
            playbackService.setQueue(new ArrayList<>(songList), position);
            startActivity(new Intent(this, NowPlayingActivity.class));
        } else {
            Log.e(TAG, "Service not bound, cannot play song: " + song.getTitle());
            Toast.makeText(this, "Playback service not ready. Please try again.", Toast.LENGTH_SHORT).show();
        }
    }


    private void checkAndRequestPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    // Show an explanation to the user *asynchronously*
                    showPermissionRationaleSnackbar(Manifest.permission.POST_NOTIFICATIONS, POST_NOTIFICATION_PERMISSION_REQUEST_CODE, "Notification permission is needed to show playback controls while the app is in the background.");
                } else {
                    // No explanation needed; request the permission
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, POST_NOTIFICATION_PERMISSION_REQUEST_CODE);
                }
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS permission already granted.");
            }
        }
    }

    private boolean checkAndRequestPermissions() { // Existing method for media permissions (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE)
        String permissionToRequest;
        String rationaleMessage;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionToRequest = Manifest.permission.READ_MEDIA_AUDIO;
            rationaleMessage = "Reading audio files is necessary to list and play music.";
        } else {
            permissionToRequest = Manifest.permission.READ_EXTERNAL_STORAGE;
            rationaleMessage = "Reading external storage is necessary to list and play music on older Android versions.";
        }

        if (ContextCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissionToRequest)) {
                showPermissionRationaleSnackbar(permissionToRequest, PERMISSION_REQUEST_CODE, rationaleMessage);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{permissionToRequest}, PERMISSION_REQUEST_CODE);
            }
            return false;
        }
        return true;
    }

    private void showPermissionRationaleSnackbar(String permission, int requestCode, String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                .setAction("Grant", view -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode))
                .show();
    }

    private void showSettingsSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                .setAction("Settings", view -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .show();
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) { // Media permissions
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Media Permission granted", Toast.LENGTH_SHORT).show();
                scanMusic();
            } else {
                // Permission denied. Explain to the user that the feature is unavailable.
                // Offer to go to settings or re-request.
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[0])) {
                    // If rationale cannot be shown (permission permanently denied with "Don't ask again")
                    showSettingsSnackbar("Media permission is required to scan music. Please enable it in app settings.");
                } else {
                    // If rationale can be shown (user just denied once)
                    Toast.makeText(this, "Media Permission denied. Cannot scan music without it.", Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == POST_NOTIFICATION_PERMISSION_REQUEST_CODE) { // Notification permission
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification Permission granted", Toast.LENGTH_SHORT).show();
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    showSettingsSnackbar("Notification permission is helpful for background playback. Please enable it in app settings if desired.");
                } else {
                    Toast.makeText(this, "Notification Permission denied. Playback notifications will not be shown.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void scanMusic() {
        Log.d(TAG, "scanMusic: Called. Starting media scan...");
        new Thread(() -> {
            MediaScanner mediaScanner = new MediaScanner();
            List<Song> scannedSongs = mediaScanner.scanForMusic(MainActivity.this);
            runOnUiThread(() -> {
                songList.clear(); // Clear existing list before adding new ones
                if (scannedSongs.isEmpty()) {
                    Log.d(TAG, "No music files found.");
                    Toast.makeText(MainActivity.this, "No music files found.", Toast.LENGTH_LONG).show();
                } else {
                    Log.d(TAG, "Found " + scannedSongs.size() + " songs.");
                    Toast.makeText(MainActivity.this, "Found " + scannedSongs.size() + " songs.", Toast.LENGTH_LONG).show();
                    songList.addAll(scannedSongs);
                    // Log details for a few songs for verification
                    for (int i = 0; i < Math.min(songList.size(), 3); i++) {
                        Song s = songList.get(i);
                        Log.d(TAG, "Song " + i + ": Title: " + s.getTitle() + ", Artist: " + s.getArtist() + ", Album: " + s.getAlbum() + ", Art URI: " + s.getAlbumArtUri());
                    }
                }
                songAdapter.setSongs(songList); // Update adapter with the new (or empty) list

                // If service is already bound, we could update its queue if needed,
                // though typically the queue is set when a song is first played.
                // if (isServiceBound && playbackService != null) {
                // playbackService.updateFullQueue(new ArrayList<>(songList));
                // }
            });
        }).start();
    }
}
