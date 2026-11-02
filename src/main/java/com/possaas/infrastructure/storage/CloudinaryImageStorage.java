package com.possaas.infrastructure.storage;

import com.possaas.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Signed upload over Cloudinary's REST API, so the secret never leaves the server. */
@Slf4j
@Component
public class CloudinaryImageStorage implements ImageStorage {
    private static final String UPLOAD_URL = "https://api.cloudinary.com/v1_1/{cloud}/image/upload";

    private final CloudinaryProperties properties;
    private final Clock clock;
    private final RestClient client;

    @Autowired
    public CloudinaryImageStorage(CloudinaryProperties properties, Clock clock) {
        this(properties, clock, RestClient.builder().requestFactory(timeouts()));
    }

    CloudinaryImageStorage(CloudinaryProperties properties, Clock clock, RestClient.Builder client) {
        this.properties = properties;
        this.clock = clock;
        this.client = client.build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    @Override
    public String upload(byte[] content, String folder) {
        if (!properties.isConfigured())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_NOT_CONFIGURED",
                    "Image storage is not configured");
        String target = properties.getFolder() + "/" + folder;
        long timestamp = clock.instant().getEpochSecond();
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "image";
            }
        });
        form.add("api_key", properties.getApiKey());
        form.add("folder", target);
        form.add("timestamp", String.valueOf(timestamp));
        form.add("signature", signature("folder=" + target + "&timestamp=" + timestamp, properties.getApiSecret()));
        try {
            Map<?, ?> response = client.post().uri(UPLOAD_URL, properties.getCloudName())
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(Map.class);
            if (response != null && response.get("secure_url") instanceof String url && !url.isBlank())
                return url;
            log.warn("Cloudinary upload returned no secure_url");
        } catch (RestClientException ex) {
            log.warn("Cloudinary upload failed: {}", ex.getMessage());
        }
        throw new BusinessException(HttpStatus.BAD_GATEWAY, "IMAGE_UPLOAD_FAILED", "Image upload failed");
    }

    /** Cloudinary signs the sorted parameters, excluding file and api_key. */
    static String signature(String sortedParameters, String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((sortedParameters + secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
