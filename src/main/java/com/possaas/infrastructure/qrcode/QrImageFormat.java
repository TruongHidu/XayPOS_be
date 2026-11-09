package com.possaas.infrastructure.qrcode;

import com.possaas.common.exception.BusinessException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public enum QrImageFormat {
    PNG(MediaType.IMAGE_PNG, "png"),
    SVG(MediaType.valueOf("image/svg+xml"), "svg");

    private final MediaType mediaType;
    private final String extension;

    QrImageFormat(MediaType mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public MediaType mediaType() { return mediaType; }
    public String extension() { return extension; }

    /** Case-insensitive {@code png} / {@code svg}; anything else is VALIDATION_ERROR. */
    public static QrImageFormat parse(String value) {
        if (value != null) {
            for (QrImageFormat format : values())
                if (format.extension.equals(value.trim().toLowerCase(Locale.ROOT))) return format;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "format must be png or svg");
    }
}
