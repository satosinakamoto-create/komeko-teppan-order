package jp.komeko.order.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.komeko.order.inventory.config.InventoryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>本物の Claude API</b> にレシートを読ませる検証。
 *
 * <p><b>ふだんのテストでは走りません。</b>お金がかかる（1 枚あたり数円）ので、
 * 環境変数 {@code ANTHROPIC_API_KEY} と、システムプロパティ {@code live.ocr=true} の
 * <b>両方</b>がそろったときだけ動きます。キーを環境変数に入れっぱなしの端末で
 * {@code mvn test} を打っても、勝手に課金されないための二重ロックです。
 *
 * <p><b>実行のしかた（キーを取得したらこれを 1 回打つ）:</b>
 * <pre>
 *   $env:ANTHROPIC_API_KEY="sk-ant-..."
 *   .\.tools\apache-maven-3.9.9\bin\mvn.cmd test -Dtest=ReceiptReaderLiveTest -D"live.ocr=true"
 * </pre>
 *
 * <p><b>何を確かめるか</b><br>
 * ① 内容が既知の合成レシート画像をこの場で描いて読ませ、店名・合計・明細・税率が
 * 正しく抜けることを<b>答え合わせ</b>します。読取の看板機能が「本当に動く」ことの証明です。<br>
 * ② {@code data/receipt-samples/} に実物のレシート写真（jpg/png）を置いてあれば、
 * それも全部読んで結果を {@code target/receipt-readings/} に書き出します。
 * 商談前の「実レシート 10 枚検証」はこのフォルダに写真を入れて流すだけです。
 *
 * <p>Spring は起動しません（確かめたいのは読取クラスそのものなので）。
 */
@DisplayName("レシート読取（本物の API を呼ぶ。ふだんは走らない）")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "live.ocr", matches = "true")
class ReceiptReaderLiveTest {

    /** 結果の書き出し先。人が目で確かめるためのもの。 */
    private static final Path OUT_DIR = Path.of("target", "receipt-readings");

    /** 実物のレシート写真を置く場所。無ければ合成レシートだけで検証する。 */
    private static final Path SAMPLES_DIR = Path.of("data", "receipt-samples");

    private ClaudeReceiptReader newReader() {
        InventoryProperties properties = new InventoryProperties(
                true, 7, 90, 1_000_000L,
                new InventoryProperties.Ocr(System.getenv("ANTHROPIC_API_KEY"), null, 120_000));
        return new ClaudeReceiptReader(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("★ 合成レシートを読ませて答え合わせする（8%と10%の混在・T番号つき）")
    void reads_a_synthetic_receipt_correctly() throws Exception {
        byte[] png = drawReceipt();
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve("synthetic-receipt.png"), png);

        ReceiptReading reading = newReader().read(png, "image/png");

        // 目で確かめられるよう、生の応答を残す
        Files.writeString(OUT_DIR.resolve("synthetic-reading.json"),
                reading.rawJson() != null ? reading.rawJson() : "(空)");
        System.out.println("=== 読取結果 ===");
        System.out.println("店名: " + reading.storeName());
        System.out.println("日付: " + reading.purchasedOn());
        System.out.println("T番号: " + reading.registrationNumber());
        System.out.println("合計: " + reading.totalAmount());
        for (ReceiptReading.Line line : reading.lines()) {
            System.out.println("  " + line.itemText() + " ×" + line.quantity()
                    + " ¥" + line.amount() + " (" + line.taxRatePercent() + "%)");
        }

        // ── 答え合わせ ──
        // 画像はこのテストが描いたものなので、正解を知っている。
        assertThat(reading.isEmpty()).as("1 行も読めていない").isFalse();
        assertThat(reading.storeName()).as("店名").contains("米粉");
        assertThat(reading.totalAmount()).as("合計金額").isEqualTo(1139);
        assertThat(reading.registrationNumber()).as("T番号")
                .isNotNull()
                .contains("7000012050002");
        assertThat(reading.lines()).as("明細の行数").hasSizeGreaterThanOrEqualTo(3);

        // 8% の行と 10% の行の両方を見分けられていること（スーパーのレシートの核心）
        List<Integer> rates = reading.lines().stream()
                .map(ReceiptReading.Line::taxRatePercent)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        assertThat(rates).as("税率の見分け").contains(8, 10);
    }

    @Test
    @DisplayName("data/receipt-samples の実物写真も全部読む（フォルダが空ならスキップ）")
    void reads_real_receipt_photos_if_present() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            System.out.println("実物レシートのフォルダがありません: " + SAMPLES_DIR
                    + "（写真を置けばここで一括検証できます）");
            return;
        }
        List<Path> photos;
        try (var stream = Files.list(SAMPLES_DIR)) {
            photos = stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
            }).sorted().toList();
        }
        if (photos.isEmpty()) {
            System.out.println("実物レシートの写真がまだありません: " + SAMPLES_DIR);
            return;
        }

        Files.createDirectories(OUT_DIR);
        ClaudeReceiptReader reader = newReader();
        int ok = 0;
        for (Path photo : photos) {
            String contentType = photo.toString().toLowerCase().endsWith(".png")
                    ? "image/png" : "image/jpeg";
            ReceiptReading reading = reader.read(Files.readAllBytes(photo), contentType);

            String name = photo.getFileName().toString();
            Files.writeString(OUT_DIR.resolve(name + ".json"),
                    reading.rawJson() != null ? reading.rawJson() : "(空)");
            System.out.printf("%-30s → 店名=%s 合計=%s 明細=%d行%n",
                    name, reading.storeName(), reading.totalAmount(), reading.lines().size());
            if (!reading.isEmpty()) {
                ok++;
            }
        }
        System.out.printf("読めた: %d / %d 枚（結果は %s に保存）%n", ok, photos.size(), OUT_DIR);

        // 実物は品質がまちまちなので「全部読めること」までは要求しない。
        // 1 枚も読めないなら、キーか実装かどちらかが壊れている。
        assertThat(ok).as("1枚も読めていない").isGreaterThan(0);
    }

    // ========================================================================
    //  合成レシートを描く
    // ========================================================================

    /**
     * 内容が既知のレシートを PNG で描く。
     *
     * <p>実物の写真を使わないのは、<b>正解が分からないと答え合わせにならない</b>からです。
     * 自分で描いた画像なら 1 円単位で正誤を判定できます。
     * 実物のかすれ・傾き・影への耐性は、上の実物フォルダのテストで別途見ます。
     */
    private byte[] drawReceipt() throws Exception {
        int w = 460;
        int h = 640;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);

        Font bold = pickFont(Font.BOLD, 22);
        Font normal = pickFont(Font.PLAIN, 18);
        int y = 40;

        g.setFont(bold);
        y = line(g, "米粉と鉄板 商店", 90, y, 34);
        g.setFont(normal);
        y = line(g, "登録番号 T7000012050002", 60, y, 28);
        y = line(g, "2026年08月31日 18:24", 60, y, 36);
        y = line(g, "------------------------------", 40, y, 30);

        y = line(g, "キャベツ 1/2         ¥218※", 40, y, 30);
        y = line(g, "豚バラスライス       ¥594※", 40, y, 30);
        y = line(g, "食器用洗剤           ¥327", 40, y, 30);
        y = line(g, "------------------------------", 40, y, 30);

        y = line(g, "小計               ¥1,139", 40, y, 30);
        g.setFont(bold);
        y = line(g, "合計               ¥1,139", 40, y, 34);
        g.setFont(normal);
        y = line(g, "(8%対象 ¥812  内消費税 ¥60)", 40, y, 28);
        y = line(g, "(10%対象 ¥327  内消費税 ¥29)", 40, y, 28);
        y = line(g, "※は軽減税率対象商品です", 40, y, 30);
        line(g, "お預り ¥2,000   お釣り ¥861", 40, y, 30);

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private int line(Graphics2D g, String text, int x, int y, int advance) {
        g.drawString(text, x, y);
        return y + advance;
    }

    /** 日本語の出るフォントを選ぶ。無ければ論理フォントに任せる。 */
    private Font pickFont(int style, int size) {
        for (String name : new String[]{"MS Gothic", "Yu Gothic", "Meiryo"}) {
            Font font = new Font(name, style, size);
            if (font.canDisplay('米')) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
