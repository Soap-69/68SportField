package com.cardshowcase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FileUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024; // 5 MB

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * Validates, saves, and returns the public-relative URL (e.g. {@code /uploads/products/abc.jpg}).
     *
     * @param file         the uploaded file (ignored if empty)
     * @param subdirectory folder inside the upload root, e.g. {@code "products"}
     */
    public String uploadFile(MultipartFile file, String subdirectory) {
        validateFile(file);

        String ext      = extension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + "." + ext;

        Path dir    = Paths.get(uploadDir).toAbsolutePath().resolve(subdirectory);
        Path target = dir.resolve(filename);

        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save uploaded file: " + e.getMessage(), e);
        }

        return "/uploads/" + subdirectory + "/" + filename;
    }

    /**
     * Deletes a file previously stored by {@link #uploadFile}.
     * Silently ignores non-existent files; logs errors rather than throwing.
     *
     * @param relativePath public-relative path returned by {@link #uploadFile},
     *                     e.g. {@code /uploads/products/abc.jpg}
     */
    public void deleteFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return;
        try {
            // strip the leading "/uploads/" to get the path below the upload root
            String sub = relativePath.replaceFirst("^/uploads/", "");
            Path   fp  = Paths.get(uploadDir).toAbsolutePath().resolve(sub);
            Files.deleteIfExists(fp);
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", relativePath, e.getMessage());
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(
                "File \"" + file.getOriginalFilename() + "\" exceeds the 5 MB size limit.");
        }
        String ext = extension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                "File type \"." + ext + "\" is not allowed. Use JPG, PNG, or WEBP.");
        }
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
