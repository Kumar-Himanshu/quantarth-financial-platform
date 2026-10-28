package com.fip.ingestion.adapters.out.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class StagingStorageService {

    private static final Logger log = LoggerFactory.getLogger(StagingStorageService.class);

    private final Path stagingRoot;
    private final long maxFileSizeBytes;

    public record StagingResult(String stagingFilePath, String fileHash, long sizeBytes) {}

    public StagingStorageService(
            @Value("${ingestion.staging.directory:/var/data/ingestion/staging}") String stagingDir,
            @Value("${ingestion.limits.max-file-size-mb:25}") long maxFileSizeMb) {
        this.stagingRoot = Paths.get(stagingDir);
        this.maxFileSizeBytes = maxFileSizeMb * 1024 * 1024;
        try {
            Files.createDirectories(this.stagingRoot);
        } catch (IOException e) {
            log.warn("Could not create staging root directory [{}], might be created on-demand: {}", stagingDir, e.getMessage());
        }
    }

    public StagingResult stageFile(String tenantId, UUID jobId, InputStream inputStream) throws IOException {
        return stageFile(tenantId, jobId, inputStream, "csv");
    }

    public StagingResult stageFile(String tenantId, UUID jobId, InputStream inputStream, String extension) throws IOException {
        Path tenantDir = stagingRoot.resolve(tenantId);
        Files.createDirectories(tenantDir);

        String ext = (extension != null && !extension.isBlank()) ? extension.toLowerCase() : "csv";
        Path targetFile = tenantDir.resolve(jobId + "." + ext + ".stage");
        MessageDigest sha256Digest;
        try {
            sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }

        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream outputStream = new FileOutputStream(targetFile.toFile())) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxFileSizeBytes) {
                    // Exceeded limit: delete partial file and abort
                    outputStream.close();
                    Files.deleteIfExists(targetFile);
                    throw new IllegalArgumentException(String.format(
                            "File size exceeds maximum permitted limit of %d MB", maxFileSizeBytes / (1024 * 1024)));
                }
                sha256Digest.update(buffer, 0, read);
                outputStream.write(buffer, 0, read);
            }
        }

        String fileHash = HexFormat.of().formatHex(sha256Digest.digest());
        log.info("Staged upload for tenant [{}] jobId [{}] at [{}] (size: {} bytes, hash: {})",
                tenantId, jobId, targetFile.toAbsolutePath(), totalBytes, fileHash);

        return new StagingResult(targetFile.toAbsolutePath().toString(), fileHash, totalBytes);
    }

    public InputStream openStagedFile(String stagingFilePath) throws IOException {
        Path path = Paths.get(stagingFilePath);
        if (!Files.exists(path)) {
            throw new IOException("Staged file not found: " + stagingFilePath);
        }
        return Files.newInputStream(path);
    }

    public void deleteStagedFile(String stagingFilePath) {
        if (stagingFilePath == null) return;
        try {
            Path path = Paths.get(stagingFilePath);
            Files.deleteIfExists(path);
            log.debug("Unlinked staged file: [{}]", stagingFilePath);
        } catch (Exception e) {
            log.warn("Failed to delete staged file [{}]: {}", stagingFilePath, e.getMessage());
        }
    }
}
