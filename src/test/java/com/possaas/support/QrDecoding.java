package com.possaas.support;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/** Test helper: decodes the QR code contained in a PNG. */
public final class QrDecoding {
    private QrDecoding() {}

    public static String decodePng(byte[] png) throws Exception {
        return decode(ImageIO.read(new ByteArrayInputStream(png)));
    }

    public static String decode(BufferedImage image) throws Exception {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }
}
