package jp.komeko.order.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jp.komeko.order.inventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Claude にレシート画像を読ませる実装。
 *
 * <p><b>なぜ公式 SDK ではなく HTTP を直接叩いているのか</b><br>
 * 呼ぶのは「画像を 1 枚送って JSON を 1 つ受け取る」1 往復だけです。
 * このために依存ライブラリを 1 つ増やすと、ビルドがネットワークに依存し、
 * バージョン更新の面倒も増えます。このプロジェクトは ZXing でも
 * 「core だけ使い、PNG 化は自前でやる」判断をしており、その方針に合わせました。
 * 呼び出し側は {@link ReceiptReader} しか知らないので、
 * あとから公式 SDK に差し替えても画面のコードは 1 行も変わりません。
 *
 * <p><b>構造化出力（structured outputs）を使う</b><br>
 * 「JSON で返して」と<b>お願いする</b>のではなく、スキーマを渡して
 * <b>その形しか返せなくする</b>機能です。前置きの文章が混ざって
 * パースに失敗する、という定番の事故が構造的に起きなくなります。
 *
 * <p><b>失敗しても例外を投げない</b><br>
 * 通信断・タイムアウト・API のエラー・安全性による拒否。どれが起きても
 * 空の結果を返すだけにします。読取はあくまで下書き作りであって、
 * ここで止まると人は何も記録できなくなってしまうからです。
 */
@Service
public class ClaudeReceiptReader implements ReceiptReader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeReceiptReader.class);

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /**
     * 読み取りの指示。
     *
     * <p>「読めなかったら null」を繰り返し書いているのは、
     * AI が空欄を埋めようとして<b>それらしい嘘を作る</b>のを防ぐためです。
     * 記録として使う以上、空欄のほうが間違いよりずっと安全です。
     */
    private static final String PROMPT = """
            これは日本の飲食店が受け取った仕入れのレシートです。記載内容を読み取ってください。

            守ってほしいこと:
            - 印字されている内容だけを書き写す。読めない項目は必ず null にする。推測で埋めない。
            - 金額はすべて税込・円の整数。カンマや「¥」は取り除く。
            - purchased_on は YYYY-MM-DD 形式。和暦は西暦に直す。年の印字がなければ null。
            - registration_number は「T」で始まる 13 桁の登録番号。印字がなければ null。
            - 明細行は印字されている順に並べる。小計・合計・お預り・お釣り・ポイントの行は明細に含めない。
            - 数量や単価が書かれていない行はよくある。amount だけ入れて quantity は null にする。
            - tax_rate_percent は、その行に適用されている消費税率。行のそばに「※」「軽」などの
              軽減税率の印があればその印から、なければレシート下部の税率ごとの内訳から判断する。
              どちらからも判断できなければ null。
            - reduced_mark は、その行に軽減税率の印が付いていれば true。
            """;

    private final InventoryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ClaudeReceiptReader(InventoryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .requestFactory(timeoutFactory(properties.ocr().timeoutMs()))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    @Override
    public boolean isAvailable() {
        return properties.ocr().isConfigured();
    }

    @Override
    public ReceiptReading read(byte[] imageBytes, String contentType) {
        if (!isAvailable()) {
            log.debug("レシート読取: API キーが未設定のため手入力運用になります");
            return ReceiptReading.empty();
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return ReceiptReading.empty();
        }

        try {
            String body = buildRequest(imageBytes, contentType);
            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-api-key", properties.ocr().apiKey())
                    .header("anthropic-version", API_VERSION)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseResponse(response);
        } catch (Exception e) {
            // 通信断・タイムアウト・API エラー。どれも「読めなかった」として扱う。
            log.warn("レシートの読み取りに失敗しました（手入力に切り替えてください）: {}", e.toString());
            return ReceiptReading.empty();
        }
    }

    // ========================================================================
    //  リクエストの組み立て
    // ========================================================================

    private String buildRequest(byte[] imageBytes, String contentType) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.ocr().model());
        root.put("max_tokens", 8192);

        // 出力の形を固定する。effort は「そこそこ考える」中庸に置く。
        ObjectNode outputConfig = root.putObject("output_config");
        outputConfig.put("effort", "medium");
        ObjectNode format = outputConfig.putObject("format");
        format.put("type", "json_schema");
        format.set("schema", receiptSchema());

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");

        ObjectNode image = content.addObject();
        image.put("type", "image");
        ObjectNode source = image.putObject("source");
        source.put("type", "base64");
        source.put("media_type", normalizeMediaType(contentType));
        source.put("data", Base64.getEncoder().encodeToString(imageBytes));

        content.addObject()
                .put("type", "text")
                .put("text", PROMPT);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 構造化出力のスキーマ。
     *
     * <p>すべての項目を {@code required} に入れ、{@code additionalProperties} を
     * false にするのが構造化出力の決まりです。
     * 「読めないかもしれない項目」は必須から外すのではなく、
     * <b>型に null を許す</b>ことで表現します。
     */
    private ObjectNode receiptSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");
        props.set("store_name", nullableType("string", "レシートに印字された店名"));
        props.set("purchased_on", nullableType("string", "取引年月日。YYYY-MM-DD 形式"));
        props.set("registration_number", nullableType("string", "インボイスの登録番号。T + 13 桁"));
        props.set("total_amount", nullableType("integer", "レシートの合計金額（税込・円）"));

        ObjectNode lines = props.putObject("lines");
        lines.put("type", "array");
        lines.put("description", "明細行。小計・合計・お預り・お釣りの行は含めない");
        ObjectNode item = lines.putObject("items");
        item.put("type", "object");
        ObjectNode lineProps = item.putObject("properties");
        lineProps.set("item_text", plainType("string", "品名。レシートの文字のまま"));
        lineProps.set("quantity", nullableType("number", "個数。印字がなければ null"));
        lineProps.set("amount", nullableType("integer", "行の合計金額（税込・円）"));
        lineProps.set("tax_rate_percent", nullableType("integer", "この行の消費税率（%）"));
        lineProps.set("tax_amount", nullableType("integer",
                "この行の消費税額（円）。レシートに印字がある場合だけ。推定はせず、無ければ null"));
        lineProps.set("reduced_mark", plainType("boolean", "軽減税率の印が付いているか"));
        item.set("required", objectMapper.createArrayNode()
                .add("item_text").add("quantity").add("amount")
                .add("tax_rate_percent").add("tax_amount").add("reduced_mark"));
        item.put("additionalProperties", false);

        schema.set("required", objectMapper.createArrayNode()
                .add("store_name").add("purchased_on").add("registration_number")
                .add("total_amount").add("lines"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode plainType(String type, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    /** 「読めなければ null」を許す型（{@code "type": ["string", "null"]}）。 */
    private ObjectNode nullableType(String type, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("type", objectMapper.createArrayNode().add(type).add("null"));
        node.put("description", description);
        return node;
    }

    private String normalizeMediaType(String contentType) {
        if (contentType == null) {
            return "image/jpeg";
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "image/png", "image/webp", "image/gif", "image/jpeg" -> lower;
            case "image/jpg" -> "image/jpeg";
            default -> "image/jpeg";
        };
    }

    // ========================================================================
    //  応答の読み取り
    // ========================================================================

    private ReceiptReading parseResponse(String response) throws Exception {
        if (response == null || response.isBlank()) {
            return ReceiptReading.empty();
        }
        JsonNode root = objectMapper.readTree(response);

        // 安全性の判断で断られることがある。その場合 content は空なので、
        // 中身を読む前に理由を確かめる（読んでから落ちると原因が分からなくなる）。
        String stopReason = root.path("stop_reason").asText("");
        if ("refusal".equals(stopReason)) {
            log.warn("レシートの読み取りが安全性の判断で行われませんでした。手入力に切り替えてください。");
            return ReceiptReading.empty();
        }

        String json = null;
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                json = block.path("text").asText();
                break;
            }
        }
        if (json == null || json.isBlank()) {
            return ReceiptReading.empty();
        }

        JsonNode parsed = objectMapper.readTree(json);
        List<ReceiptReading.Line> lines = new ArrayList<>();
        for (JsonNode line : parsed.path("lines")) {
            String itemText = text(line, "item_text");
            if (itemText == null || itemText.isBlank()) {
                continue;   // 品名のない行は使いようがない
            }
            lines.add(new ReceiptReading.Line(
                    itemText,
                    decimal(line, "quantity"),
                    integer(line, "amount"),
                    integer(line, "tax_rate_percent"),
                    integer(line, "tax_amount"),
                    line.path("reduced_mark").asBoolean(false)));
        }

        return new ReceiptReading(
                text(parsed, "store_name"),
                text(parsed, "purchased_on"),
                text(parsed, "registration_number"),
                integer(parsed, "total_amount"),
                lines,
                json);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText(null);
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || !value.isNumber() ? null : value.asInt();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || !value.isNumber() ? null : value.decimalValue();
    }
}
