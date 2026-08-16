package jp.komeko.order.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jp.komeko.order.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * QR コードを作るサービス。
 *
 * <p>お客さんに読んでもらう QR には、単純にメニューの URL を入れます。
 * テイクアウトの共通 QR なので 1 種類だけで足りますが、
 * 「どのチラシ・どの POP から来たか」を集計したいこともあるので、
 * {@code ?src=counter} のようなパラメータを付けられるようにしています。
 *
 * <p>ZXing の core だけを使い、PNG 化は Java 標準の {@link ImageIO} で行っています。
 * （zxing の javase パッケージを足すと依存が増えるため。やっていることは同じです）
 */
@Service
public class QrCodeService {

    private final AppProperties properties;

    public QrCodeService(AppProperties properties) {
        this.properties = properties;
    }

    /** 注文ページの URL を返す（QR に埋め込む文字列）。 */
    public String orderPageUrl(String source) {
        String base = properties.normalizedBaseUrl() + "/";
        if (source == null || source.isBlank()) {
            return base;
        }
        return base + "?src=" + java.net.URLEncoder.encode(source, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 文字列から QR コードの PNG 画像を作る。
     *
     * @param text  QR に埋め込む文字列（URL など）
     * @param size  1 辺のピクセル数
     * @param quiet 周囲の余白（モジュール数）。印刷して読ませるなら 4 以上が安全
     */
    public byte[] toPngBytes(String text, int size, int quiet) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, quiet);
        // 誤り訂正レベル M（約15%汚れても読める）。
        // 店頭でソースが飛んだり傷が付くことを考えると、H（30%）にしてもよい。
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);

        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage image = toImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("QR コードを生成できませんでした: " + text, e);
        } catch (IOException e) {
            throw new UncheckedIOException("QR コードの PNG 変換に失敗しました", e);
        }
    }

    /** 既定サイズ（512px・余白 4）で生成する。 */
    public byte[] toPngBytes(String text) {
        return toPngBytes(text, 512, 4);
    }

    /**
     * 白黒のビットの並び（BitMatrix）を画像に変換する。
     * 1 ドットずつ色を置いているだけで、難しいことはしていません。
     */
    private BufferedImage toImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final int black = 0x00000000;
        final int white = 0x00FFFFFF;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? black : white);
            }
        }
        return image;
    }
}
