package com.possaas.infrastructure.storage;

public interface ImageStorage {
    /** Stores the image and returns its public HTTPS URL. */
    String upload(byte[] content, String folder);
}
