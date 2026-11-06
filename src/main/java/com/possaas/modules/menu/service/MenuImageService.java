package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.infrastructure.storage.ImageStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MenuImageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private final MenuAccessService access;
    private final ImageStorage storage;

    public String upload(MultipartFile file) {
        var actor = access.require(false);
        if (file.getSize() > MAX_BYTES)
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Image is larger than 5 MB");
        byte[] content = read(file);
        if (!isSupported(content))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Only JPG, PNG or WebP images are accepted");
        return storage.upload(content, actor.restaurantId() + "/menu");
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Image could not be read");
        }
    }

    // The client's name and content type are not evidence of what the bytes are.
    private boolean isSupported(byte[] content) {
        return startsWith(content, JPEG, 0) || startsWith(content, PNG, 0)
                || (startsWith(content, RIFF, 0) && startsWith(content, WEBP, 8));
    }

    private boolean startsWith(byte[] content, byte[] signature, int offset) {
        int end = offset + signature.length;
        return content.length >= end
                && Arrays.equals(content, offset, end, signature, 0, signature.length);
    }
}
