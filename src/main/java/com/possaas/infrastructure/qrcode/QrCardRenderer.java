package com.possaas.infrastructure.qrcode;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Printable A6 (300 dpi) table card: restaurant name, QR code, table label and a scan hint. */
@Component
@RequiredArgsConstructor
public class QrCardRenderer {
    public static final int WIDTH = 1240;
    public static final int HEIGHT = 1748;
    static final int QR_SIZE = 920;
    private static final int PADDING = 100;
    private static final int TEXT_WIDTH = WIDTH - 2 * PADDING;
    private static final Color INK = new Color(0x1F, 0x23, 0x28);
    private static final Color MUTED = new Color(0x5F, 0x66, 0x6D);
    private static final Color RULE = new Color(0xD0, 0xD5, 0xDB);
    /** Families known to carry Vietnamese glyphs; DejaVu Sans is installed in the runtime image. */
    private static final List<String> PREFERRED_FAMILIES = List.of("DejaVu Sans", "Noto Sans", "Be Vietnam Pro",
            "Segoe UI", "Arial", "Liberation Sans", "Helvetica");
    private static final String VIETNAMESE_SAMPLE = "ĐđƯưƠơẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹ";

    private final QrCodeRenderer codes;
    private volatile String family;

    /**
     * @param title    restaurant name (top, bold)
     * @param subtitle main label under the QR, e.g. table name
     * @param detail   optional secondary label, e.g. table code; null or blank to omit
     * @param caption  scan instruction at the bottom
     * @param url      absolute URL encoded in the QR
     */
    public record QrCard(String title, String subtitle, String detail, String caption, String url) {
        @Override public String toString() { return "QrCard[url=[REDACTED]]"; }
    }

    public byte[] render(QrCard card) {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // Cutting guide.
            g.setColor(RULE);
            g.setStroke(new BasicStroke(4f));
            g.draw(new RoundRectangle2D.Float(24, 24, WIDTH - 48, HEIGHT - 48, 48, 48));

            String family = family();
            Font bold = new Font(family, Font.BOLD, 72);
            Font regular = new Font(family, Font.PLAIN, 48);

            centered(g, card.title(), bold, 76, 40, INK, 210);
            g.setColor(RULE);
            g.fillRect(PADDING, 270, TEXT_WIDTH, 4);

            int qrX = (WIDTH - QR_SIZE) / 2;
            int qrY = 300;
            // Quiet zone is part of the drawn square, so the white card around it stays scannable.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            codes.draw(g, codes.encode(card.url()), qrX, qrY, QR_SIZE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int y = qrY + QR_SIZE + 140;
            centered(g, card.subtitle(), bold, 120, 56, INK, y);
            if (card.detail() != null && !card.detail().isBlank()) {
                y += 90;
                centered(g, card.detail(), regular, 56, 36, MUTED, y);
            }
            centered(g, card.caption(), regular, 50, 32, MUTED, HEIGHT - 120);
        } finally {
            g.dispose();
        }
        return Images.png(image);
    }

    /** Draws one line centered horizontally at the given baseline, shrinking then ellipsizing to fit. */
    private void centered(Graphics2D g, String raw, Font base, float maxSize, float minSize, Color color, int baseline) {
        if (raw == null || raw.isBlank()) return;
        String text = Normalizer.normalize(raw.strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        Font font = base.deriveFont(maxSize);
        for (float size = maxSize; size >= minSize; size -= 2) {
            font = base.deriveFont(size);
            if (g.getFontMetrics(font).stringWidth(text) <= TEXT_WIDTH) break;
        }
        FontMetrics metrics = g.getFontMetrics(font);
        text = ellipsize(metrics, text);
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, (WIDTH - metrics.stringWidth(text)) / 2, baseline);
    }

    private static String ellipsize(FontMetrics metrics, String text) {
        if (metrics.stringWidth(text) <= TEXT_WIDTH) return text;
        int[] points = text.codePoints().toArray();
        for (int length = points.length - 1; length > 0; length--) {
            String candidate = new String(points, 0, length).stripTrailing() + "…";
            if (metrics.stringWidth(candidate) <= TEXT_WIDTH) return candidate;
        }
        return "…";
    }

    private String family() {
        String resolved = family;
        if (resolved == null) family = resolved = resolveFamily();
        return resolved;
    }

    static String resolveFamily() {
        Set<String> installed = Set.copyOf(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : PREFERRED_FAMILIES) {
            if (installed.contains(candidate) && new Font(candidate, Font.PLAIN, 12).canDisplayUpTo(VIETNAMESE_SAMPLE) == -1)
                return candidate;
        }
        return Font.SANS_SERIF;
    }
}
