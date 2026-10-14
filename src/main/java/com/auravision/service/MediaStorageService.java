package com.auravision.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Optional durable object storage. If all R2 settings are absent, local storage
 * remains available for development; Render's local filesystem is not durable.
 */
@Service
@Slf4j
public class MediaStorageService {
    private static final String R2_PREFIX = "r2://";

    @Value("${app.storage-dir:./storage}") private String storageDir;
    @Value("${cloudflare.r2.account-id:}") private String accountId;
    @Value("${cloudflare.r2.access-key-id:}") private String accessKeyId;
    @Value("${cloudflare.r2.secret-access-key:}") private String secretAccessKey;
    @Value("${cloudflare.r2.bucket:}") private String bucket;

    private S3Client s3;

    @PostConstruct
    void initialize() {
        boolean any = present(accountId) || present(accessKeyId) || present(secretAccessKey) || present(bucket);
        boolean all = present(accountId) && present(accessKeyId) && present(secretAccessKey) && present(bucket);
        if (any && !all) {
            throw new IllegalStateException("Cloudflare R2 is partially configured. Set account ID, access key, secret key, and bucket together.");
        }
        if (all) {
            s3 = S3Client.builder()
                    .endpointOverride(java.net.URI.create("https://" + accountId.trim() + ".r2.cloudflarestorage.com"))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId.trim(), secretAccessKey.trim())))
                    .build();
            log.info("Durable media storage enabled (Cloudflare R2).");
        } else {
            log.warn("Cloudflare R2 is not configured; generated media will use local storage, which may be lost on host restart.");
        }
    }

    public String store(Long generationId, String filename, byte[] bytes, String contentType) throws IOException {
        Objects.requireNonNull(bytes, "media bytes");
        if (s3 != null) {
            String key = "media/" + generationId + "/" + safeFilename(filename);
            s3.putObject(PutObjectRequest.builder().bucket(bucket.trim()).key(key)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build(), RequestBody.fromBytes(bytes));
            return R2_PREFIX + key;
        }
        Path root = Path.of(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve(safeFilename(filename)).normalize();
        if (!file.getParent().equals(root)) throw new IOException("Invalid media filename");
        Files.write(file, bytes);
        return file.toString();
    }

    public boolean exists(String storageRef) {
        if (storageRef == null || storageRef.isBlank()) return false;
        try {
            if (isR2(storageRef)) {
                if (s3 == null) return false;
                s3.headObject(HeadObjectRequest.builder().bucket(bucket.trim()).key(objectKey(storageRef)).build());
                return true;
            }
            return Files.isRegularFile(Path.of(storageRef));
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public long size(String storageRef) throws IOException {
        if (isR2(storageRef)) {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket.trim()).key(objectKey(storageRef)).build());
            return response.contentLength();
        }
        return Files.size(Path.of(storageRef));
    }

    public InputStream openStream(String storageRef) throws IOException {
        if (isR2(storageRef)) {
            if (s3 == null) throw new IOException("R2 storage is not configured");
            ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket.trim()).key(objectKey(storageRef)).build());
            return stream;
        }
        return Files.newInputStream(Path.of(storageRef));
    }

    public void delete(String storageRef) throws IOException {
        if (storageRef == null || storageRef.isBlank()) return;
        if (isR2(storageRef)) {
            if (s3 != null) s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket.trim()).key(objectKey(storageRef)).build());
            return;
        }
        Files.deleteIfExists(Path.of(storageRef));
    }

    public boolean isR2(String storageRef) {
        return storageRef != null && storageRef.startsWith(R2_PREFIX);
    }

    private String objectKey(String storageRef) {
        String key = storageRef.substring(R2_PREFIX.length());
        if (key.isBlank() || key.startsWith("/") || key.contains("..") || key.contains("\\")) {
            throw new IllegalArgumentException("Invalid object storage reference");
        }
        return key;
    }

    private static String safeFilename(String filename) {
        String name = Path.of(filename).getFileName().toString();
        if (!name.matches("[0-9]+\\.(png|jpg|webp|mp4)")) {
            throw new IllegalArgumentException("Invalid generated media filename");
        }
        return name;
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    @PreDestroy
    void close() { if (s3 != null) s3.close(); }
}
