package com.example.musicplayer;

public class Song {
    private long id;
    private String path;
    private String title;
    private String artist;
    private String album;
    private long duration;
    private String albumArtUri;

    public Song(long id, String path, String title, String artist, String album, long duration, String albumArtUri) {
        this.id = id;
        this.path = path;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.albumArtUri = albumArtUri;
    }

    public long getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public long getDuration() {
        return duration;
    }

    public String getAlbumArtUri() {
        return albumArtUri;
    }
}
