package com.possaas.infrastructure.qrcode;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Rendered QR image. Content embeds a bearer menu token: never cache, store or log it. */
public record QrImage(byte[] content, MediaType mediaType, String filename) {
    public static QrImage png(byte[] content, String baseName) {
        return new QrImage(content, MediaType.IMAGE_PNG, baseName + ".png");
    }

    public static QrImage of(QrImageFormat format, byte[] content, String baseName) {
        return new QrImage(content, format.mediaType(), baseName + "." + format.extension());
    }

    public static QrImage svg(String content, String baseName) {
        return of(QrImageFormat.SVG, content.getBytes(StandardCharsets.UTF_8), baseName);
    }

    public ResponseEntity<byte[]> toResponse(boolean download) {
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(filename).build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(mediaType)
                .contentLength(content.length)
                .header("Content-Disposition", disposition.toString())
                .body(content);
    }

    /** ASCII-only filename fragment: strips Vietnamese diacritics, keeps [a-z0-9-], falls back when empty. */
    public static String slug(String value, String fallback) {
        if (value == null) return fallback;
        String ascii = Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 50) slug = slug.substring(0, 50).replaceAll("-+$", "");
        return slug.isEmpty() ? fallback : slug;
    }

    @Override
    public String toString() {
        return "QrImage[mediaType=" + mediaType + ", bytes=" + content.length + ", filename=" + filename + "]";
    }
}
