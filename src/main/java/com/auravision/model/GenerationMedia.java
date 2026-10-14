package com.auravision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Media bytes stored in the same PostgreSQL database as generation metadata.
 * Kept separate from Generation so gallery list queries do not load large blobs.
 */
@Entity
@Table(name = "generation_media")
public class GenerationMedia {
    @Id
    @Column(name = "generation_id")
    private Long generationId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "media_data", nullable = false)
    private byte[] mediaData;

    @Column(name = "content_type", length = 255)
    private String contentType;

    protected GenerationMedia() {}

    public GenerationMedia(Long generationId, byte[] mediaData, String contentType) {
        this.generationId = generationId;
        this.mediaData = mediaData;
        this.contentType = contentType;
    }

    public Long getGenerationId() { return generationId; }
    public byte[] getMediaData() { return mediaData; }
    public String getContentType() { return contentType; }
}
