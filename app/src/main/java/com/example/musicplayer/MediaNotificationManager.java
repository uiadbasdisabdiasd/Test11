package com.example.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.v4.media.session.MediaSessionCompat;


import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
// import androidx.media.app.NotificationCompat.MediaStyle; // Correct import for MediaStyle

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;

public class MediaNotificationManager {

    private static final String TAG = "MediaNotificationMgr";
    public static final String CHANNEL_ID = "music_playback_channel";
    public static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PREVIOUS = "com.example.musicplayer.ACTION_PREVIOUS";
    public static final String ACTION_PLAY_PAUSE = "com.example.musicplayer.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT";
    public static final String ACTION_STOP_SERVICE = "com.example.musicplayer.ACTION_STOP_SERVICE";


    private PlaybackService service;
    private NotificationManagerCompat notificationManager;


    public MediaNotificationManager(PlaybackService service) {
        this.service = service;
        this.notificationManager = NotificationManagerCompat.from(service);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = service.getString(R.string.notification_channel_name); // Add this string to strings.xml
            String description = service.getString(R.string.notification_channel_description); // Add this string to strings.xml
            int importance = NotificationManager.IMPORTANCE_LOW; // Low importance to avoid sound/vibration but still show
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null, null); // No sound for music playback notifications
            channel.enableLights(false);
            channel.enableVibration(false);

            NotificationManager manager = service.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                android.util.Log.d(TAG, "Notification channel created: " + CHANNEL_ID);
            } else {
                android.util.Log.e(TAG, "Failed to get NotificationManager to create channel.");
            }
        }
    }

    public void buildNotificationAndStartForeground(Song currentSong, boolean isPlaying, MediaSessionCompat.Token mediaSessionToken) {
        if (currentSong == null) {
            android.util.Log.d(TAG, "Cannot build notification, currentSong is null");
            service.stopForeground(true);
            hideNotification();
            return;
        }

        // Intent to open NowPlayingActivity when notification is clicked
        Intent contentIntent = new Intent(service, NowPlayingActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(service, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        // Create PendingIntents for actions
        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(service, 0,
                new Intent(ACTION_PREVIOUS).setPackage(service.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(service, 0,
                new Intent(ACTION_PLAY_PAUSE).setPackage(service.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(service, 0,
                new Intent(ACTION_NEXT).setPackage(service.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        
        // PendingIntent stopPendingIntent = PendingIntent.getBroadcast(service, 0,
        // new Intent(ACTION_STOP_SERVICE).setPackage(service.getPackageName()),
        // PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));


        androidx.media.app.NotificationCompat.MediaStyle mediaStyle = new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionToken)
                .setShowActionsInCompactView(0, 1, 2); // Previous, Play/Pause, Next


        NotificationCompat.Builder builder = new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note) // Ensure this drawable exists
                .setContentTitle(currentSong.getTitle())
                .setContentText(currentSong.getArtist())
                .setContentIntent(contentPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying) // Ongoing if playing, dismissible if paused
                .setStyle(mediaStyle)
                .addAction(R.drawable.ic_skip_previous, "Previous", prevPendingIntent)
                .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow, isPlaying ? "Pause" : "Play", playPausePendingIntent)
                .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent);
                // .addAction(R.drawable.ic_close, "Stop", stopPendingIntent); // Optional stop action

        // Load album art asynchronously
        Glide.with(service)
                .asBitmap()
                .load(currentSong.getAlbumArtUri())
                .error(R.drawable.ic_default_album_art) // Default art if loading fails
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                        builder.setLargeIcon(resource);
                        Notification notification = builder.build();
                        // notificationManager.notify(NOTIFICATION_ID, notification); // Don't notify here, let service handle foreground
                        service.startForeground(NOTIFICATION_ID, notification);
                        android.util.Log.d(TAG, "Notification built and service put in foreground (with album art). Playing: " + isPlaying);
                    }

                    @Override
                    public void onLoadFailed(android.graphics.drawable.Drawable errorDrawable) {
                        super.onLoadFailed(errorDrawable);
                        // Use default album art if Glide fails
                        Bitmap defaultAlbumArt = BitmapFactory.decodeResource(service.getResources(), R.drawable.ic_default_album_art);
                        if (defaultAlbumArt != null) builder.setLargeIcon(defaultAlbumArt);
                        Notification notification = builder.build();
                        // notificationManager.notify(NOTIFICATION_ID, notification);
                        service.startForeground(NOTIFICATION_ID, notification);
                        android.util.Log.d(TAG, "Notification built and service put in foreground (default art). Playing: " + isPlaying);
                    }
                });
        
        // Fallback for immediate notification display before Glide finishes (or if it fails quickly)
        // This ensures the service goes to foreground state promptly.
        // The notification will then update with album art once Glide loads it.
        if (!Glide.with(service).isPaused()) { // Check if Glide is active
             Bitmap defaultAlbumArt = BitmapFactory.decodeResource(service.getResources(), R.drawable.ic_default_album_art);
             if (defaultAlbumArt != null) builder.setLargeIcon(defaultAlbumArt);
             Notification notification = builder.build();
             service.startForeground(NOTIFICATION_ID, notification);
             android.util.Log.d(TAG, "Notification built (initial) and service put in foreground. Playing: " + isPlaying);
        }
    }


    public void hideNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
        android.util.Log.d(TAG, "Notification hidden");
    }
}
