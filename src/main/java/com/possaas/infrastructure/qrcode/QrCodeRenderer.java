package com.possaas.infrastructure.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders a URL into a QR code. Output is generated on demand and never stored: QR content carries a
 * bearer token that rotation must be able to retire.
 */
@Component
public class QrCodeRenderer {
    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 2048;
    public static final int DEFAULT_SIZE = 512;
    /** Quiet zone required by ISO/IEC 18004, in modules. */
    static final int QUIET_ZONE = 4;

    /** Raw module matrix without quiet zone (one bit per module). */
    public BitMatrix encode(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException failure) {
            throw new IllegalStateException("QR content cannot be encoded", failure);
        }
    }

    public byte[] png(String content, int size) {
        BitMatrix matrix = encode(content);
        int px = Math.max(size, modulesWithQuietZone(matrix));
        var image = new BufferedImage(px, px, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, px, px);
            draw(g, matrix, 0, 0, px);
        } finally {
            g.dispose();
        }
        return Images.png(image);
    }

    public String svg(String content, int size) {
        BitMatrix matrix = encode(content);
        int total = modulesWithQuietZone(matrix);
        var path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            int x = 0;
            while (x < matrix.getWidth()) {
                if (!matrix.get(x, y)) { x++; continue; }
                int start = x;
                while (x < matrix.getWidth() && matrix.get(x, y)) x++;
                path.append('M').append(start + QUIET_ZONE).append(' ').append(y + QUIET_ZONE)
                        .append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + size + "\" height=\"" + size
                + "\" viewBox=\"0 0 " + total + " " + total + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + total + "\" height=\"" + total + "\" fill=\"#ffffff\"/>"
                + "<path fill=\"#000000\" d=\"" + path + "\"/></svg>\n";
    }

    /**
     * Draws the code including its quiet zone into a square of {@code sizePx} at (x, y). Modules are snapped to
     * whole pixels and the remainder is split evenly around the code so scanners see crisp edges.
     */
    void draw(Graphics2D g, BitMatrix matrix, int x, int y, int sizePx) {
        int total = modulesWithQuietZone(matrix);
        int scale = Math.max(1, sizePx / total);
        int offset = (sizePx - total * scale) / 2 + QUIET_ZONE * scale;
        g.setColor(Color.BLACK);
        for (int row = 0; row < matrix.getHeight(); row++)
            for (int col = 0; col < matrix.getWidth(); col++)
                if (matrix.get(col, row)) g.fillRect(x + offset + col * scale, y + offset + row * scale, scale, scale);
    }

    static int modulesWithQuietZone(BitMatrix matrix) {
        return matrix.getWidth() + 2 * QUIET_ZONE;
    }
}
