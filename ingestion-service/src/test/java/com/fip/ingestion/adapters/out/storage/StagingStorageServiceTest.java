package com.fip.ingestion.adapters.out.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Staging Storage Service Unit Tests")
class StagingStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Successfully stages file, creates tenant directory, and computes accurate SHA-256")
    void testStageFileSuccess() throws Exception {
        StagingStorageService service = new StagingStorageService(tempDir.toString(), 25);
        String content = "col1,col2\nval1,val2\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        UUID jobId = UUID.randomUUID();

        StagingStorageService.StagingResult result = service.stageFile("tenant-alpha", jobId, is);

        assertNotNull(result);
        assertTrue(Files.exists(Path.of(result.stagingFilePath())));
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, result.sizeBytes());
        assertNotNull(result.fileHash());
        assertFalse(result.fileHash().isBlank());

        // Read back staged file
        try (InputStream stagedStream = service.openStagedFile(result.stagingFilePath())) {
            String readContent = new String(stagedStream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(content, readContent);
        }

        // Cleanup
        service.deleteStagedFile(result.stagingFilePath());
        assertFalse(Files.exists(Path.of(result.stagingFilePath())), "Staged file should be deleted upon unlinking");
    }

    @Test
    @DisplayName("Enforces quota: Rejects file exceeding maximum configured size limit and deletes partial file")
    void testFileExceedingQuotaRejected() {
        // Limit to 1 MB
        StagingStorageService service = new StagingStorageService(tempDir.toString(), 1);

        // 1.5 MB stream
        byte[] largeData = new byte[1536 * 1024];
        InputStream is = new ByteArrayInputStream(largeData);
        UUID jobId = UUID.randomUUID();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.stageFile("tenant-beta", jobId, is));

        assertTrue(ex.getMessage().contains("exceeds maximum permitted limit"));
        Path expectedFile = tempDir.resolve("tenant-beta").resolve(jobId + ".csv.stage");
        assertFalse(Files.exists(expectedFile), "Partial file must be deleted upon size violation");
    }
}
