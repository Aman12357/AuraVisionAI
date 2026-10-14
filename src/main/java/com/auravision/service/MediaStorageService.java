package com.auravision.service;

import com.auravision.model.GenerationMedia;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Stores new generated media in PostgreSQL for persistence without an extra
 * object-storage account. Existing local paths remain readable for compatibility.
 * Database storage is best for an MVP / low-volume use because videos consume DB quota.
 */
@Service
@Slf4j
public class MediaStorageService {
    private static final String DB_PREFIX = "db://";

    private final JdbcTemplate jdbc;
    @Value("${app.storage-dir:./storage}") private String storageDir;

    public MediaStorageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String store(Long generationId, String filename, byte[] bytes, String contentType) throws IOException {
        Objects.requireNonNull(bytes, "media bytes");
        if (generationId == null || generationId < 1) throw new IOException("Invalid generation ID");
        // Guard against unexpectedly large uploads exhausting the free database quota.
        if (bytes.length > 40 * 1024 * 1024) throw new IOException("Generated media exceeds the 40 MB database storage limit.");
        try {
            jdbc.update("DELETE FROM generation_media WHERE generation_id = ?", generationId);
            jdbc.update("INSERT INTO generation_media (generation_id, media_data, content_type) VALUES (?, ?, ?)",
                    generationId, bytes, contentType == null ? "application/octet-stream" : contentType);
            return DB_PREFIX + generationId;
        } catch (RuntimeException e) {
            log.error("Could not store generation {} in the database", generationId, e);
            throw new IOException("Could not save generated media to database storage.", e);
        }
    }

    public boolean exists(String storageRef) {
        if (storageRef == null || storageRef.isBlank()) return false;
        if (isDatabase(storageRef)) {
            try {
                Long id = databaseId(storageRef);
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM generation_media WHERE generation_id = ?", Integer.class, id);
                return count != null && count > 0;
            } catch (RuntimeException e) {
                return false;
            }
        }
        try { return Files.isRegularFile(Path.of(storageRef)); }
        catch (RuntimeException e) { return false; }
    }

    public long size(String storageRef) throws IOException {
        if (isDatabase(storageRef)) return load(storageRef).getMediaData().length;
        return Files.size(Path.of(storageRef));
    }

    public InputStream openStream(String storageRef) throws IOException {
        if (isDatabase(storageRef)) return new ByteArrayInputStream(load(storageRef).getMediaData());
        return Files.newInputStream(Path.of(storageRef));
    }

    public void delete(String storageRef) throws IOException {
        if (storageRef == null || storageRef.isBlank()) return;
        if (isDatabase(storageRef)) {
            jdbc.update("DELETE FROM generation_media WHERE generation_id = ?", databaseId(storageRef));
            return;
        }
        Files.deleteIfExists(Path.of(storageRef));
    }

    public boolean isDatabase(String storageRef) {
        return storageRef != null && storageRef.startsWith(DB_PREFIX);
    }

    private GenerationMedia load(String storageRef) throws IOException {
        try {
            return jdbc.queryForObject(
                    "SELECT generation_id, media_data, content_type FROM generation_media WHERE generation_id = ?",
                    (rs, row) -> new GenerationMedia(
                            rs.getLong("generation_id"), rs.getBytes("media_data"), rs.getString("content_type")),
                    databaseId(storageRef));
        } catch (RuntimeException e) {
            throw new IOException("Stored media was not found in the database.", e);
        }
    }

    private Long databaseId(String storageRef) {
        String value = storageRef.substring(DB_PREFIX.length());
        if (!value.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("Invalid database media reference");
        return Long.parseLong(value);
    }
}
