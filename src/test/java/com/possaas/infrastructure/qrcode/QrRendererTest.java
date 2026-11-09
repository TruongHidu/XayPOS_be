package com.possaas.infrastructure.qrcode;

import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.config.PublicMenuProperties;
import com.possaas.support.QrDecoding;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class QrRendererTest {
    private static final String URL = "https://pos.example.com/menu/qr/" + "A".repeat(21) + "_" + "b".repeat(21);
    private final QrCodeRenderer codes = new QrCodeRenderer();
    private final QrCardRenderer cards = new QrCardRenderer(codes);

    @Test
    void pngDecodesToTheExactUrlAtEveryAllowedSize() throws Exception {
        for (int size : new int[] {QrCodeRenderer.MIN_SIZE, QrCodeRenderer.DEFAULT_SIZE, QrCodeRenderer.MAX_SIZE}) {
            BufferedImage image = read(codes.png(URL, size));
            assertThat(image.getWidth()).isEqualTo(size);
            assertThat(image.getHeight()).isEqualTo(size);
            assertThat(decode(image)).isEqualTo(URL);
        }
    }

    @Test
    void svgIsWellFormedAndSized() throws Exception {
        String svg = codes.svg(URL, 300);
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        var root = document.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("svg");
        assertThat(root.getAttribute("width")).isEqualTo("300");
        int modules = QrCodeRenderer.modulesWithQuietZone(codes.encode(URL));
        assertThat(root.getAttribute("viewBox")).isEqualTo("0 0 " + modules + " " + modules);
        assertThat(document.getElementsByTagName("path").item(0).getAttributes().getNamedItem("d").getNodeValue())
                .startsWith("M" + QrCodeRenderer.QUIET_ZONE + " " + QrCodeRenderer.QUIET_ZONE);
    }

    @Test
    void cardIsA6AndItsQrIsScannableWithLongVietnameseNames() throws Exception {
        var card = new QrCardRenderer.QrCard("Nhà hàng Hương Việt — Chi nhánh Đống Đa ".repeat(4),
                "Bàn ngoài trời số 12 cạnh cửa sổ lớn tầng hai", "Mã bàn: T2-12", "Quét mã để xem menu & gọi món", URL);
        BufferedImage image = read(cards.render(card));
        assertThat(image.getWidth()).isEqualTo(QrCardRenderer.WIDTH);
        assertThat(image.getHeight()).isEqualTo(QrCardRenderer.HEIGHT);
        assertThat(decode(image)).isEqualTo(URL);
        assertThat(card.toString()).doesNotContain(URL);
    }

    @Test
    void cardToleratesMissingOptionalLabels() throws Exception {
        var card = new QrCardRenderer.QrCard("Quán", "Menu nhà hàng", null, "Quét mã", URL);
        assertThat(decode(read(cards.render(card)))).isEqualTo(URL);
    }

    @Test
    void formatParsingIsCaseInsensitiveAndRejectsOthers() {
        assertThat(QrImageFormat.parse("PNG")).isEqualTo(QrImageFormat.PNG);
        assertThat(QrImageFormat.parse(" svg ")).isEqualTo(QrImageFormat.SVG);
        for (String invalid : new String[] {null, "", "jpg", "pdf"})
            assertThatThrownBy(() -> QrImageFormat.parse(invalid)).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void filenameSlugIsAsciiOnly() {
        assertThat(QrImage.slug("Bàn Đặc biệt 01", "table")).isEqualTo("ban-dac-biet-01");
        assertThat(QrImage.slug("\"; rm -rf /", "table")).isEqualTo("rm-rf");
        assertThat(QrImage.slug("***", "table")).isEqualTo("table");
        assertThat(QrImage.slug(null, "table")).isEqualTo("table");
    }

    @Test
    void responseIsNeverCachedAndHonorsDownload() {
        var image = QrImage.png(new byte[] {1, 2, 3}, "qr-ban-b01");
        var inline = image.toResponse(false);
        assertThat(inline.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(inline.getHeaders().getFirst("Content-Disposition")).startsWith("inline").contains("qr-ban-b01.png");
        assertThat(image.toResponse(true).getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        assertThat(image.toString()).doesNotContain("[1, 2, 3]");
    }

    @Test
    void baseUrlIsJoinedAndValidated() {
        var menu = new PublicMenuProperties();
        menu.setBaseUrl("https://pos.example.com//");
        menu.setRequireHttps(true);
        menu.afterPropertiesSet();
        assertThat(menu.absolute("/menu/qr/x")).isEqualTo("https://pos.example.com/menu/qr/x");
        for (String invalid : new String[] {"pos.example.com", "ftp://pos.example.com", "https://pos.example.com?a=1", "http://pos.example.com", ""}) {
            var bad = new PublicMenuProperties();
            bad.setBaseUrl(invalid);
            bad.setRequireHttps(true);
            assertThatThrownBy(bad::afterPropertiesSet).as(invalid).isInstanceOf(IllegalStateException.class);
        }
    }

    private static BufferedImage read(byte[] png) throws Exception {
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static String decode(BufferedImage image) throws Exception {
        return QrDecoding.decode(image);
    }
}
