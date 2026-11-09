package com.possaas.infrastructure.qrcode;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

final class Images {
    private Images() {}

    static byte[] png(RenderedImage image) {
        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("PNG writer unavailable");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return out.toByteArray();
    }
}
