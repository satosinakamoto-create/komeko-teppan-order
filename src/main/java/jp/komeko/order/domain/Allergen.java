package jp.komeko.order.domain;

/**
 * アレルゲン（食物アレルギー原因物質）。
 *
 * <p>日本の食品表示基準では、必ず表示しなければならない「特定原材料」8 品目と、
 * 表示が推奨される「特定原材料に準ずるもの」20 品目が定められています。
 * ここでは 8 品目を必須扱いにし、飲食店で出やすい準ずるものを一部収録しました。
 *
 * <p>米粉のお店は「小麦不使用（グルテンフリー）」が最大の売りになるので、
 * {@link #WHEAT} を含むかどうかを画面で目立たせています。
 *
 * <p>※ 実際の営業でお客さんに案内する内容は、必ず原材料の仕入元表示を
 * 確認したうえで店舗側の責任で登録してください。本システムは表示を補助するだけです。
 */
public enum Allergen {

    // ── 特定原材料 8 品目（表示義務） ─────────────────────────────
    SHRIMP("えび", true),
    CRAB("かに", true),
    WALNUT("くるみ", true),
    WHEAT("小麦", true),
    BUCKWHEAT("そば", true),
    EGG("卵", true),
    MILK("乳", true),
    PEANUT("落花生", true),

    // ── 特定原材料に準ずるもの（表示推奨）から主要なもの ──────────
    ALMOND("アーモンド", false),
    ABALONE("あわび", false),
    SQUID("いか", false),
    SALMON_ROE("いくら", false),
    ORANGE("オレンジ", false),
    CASHEW("カシューナッツ", false),
    KIWI("キウイフルーツ", false),
    BEEF("牛肉", false),
    SESAME("ごま", false),
    SALMON("さけ", false),
    MACKEREL("さば", false),
    SOY("大豆", false),
    CHICKEN("鶏肉", false),
    BANANA("バナナ", false),
    PORK("豚肉", false),
    MATSUTAKE("まつたけ", false),
    PEACH("もも", false),
    YAM("やまいも", false),
    APPLE("りんご", false),
    GELATIN("ゼラチン", false);

    private final String label;
    private final boolean mandatory;

    Allergen(String label, boolean mandatory) {
        this.label = label;
        this.mandatory = mandatory;
    }

    /** 画面表示用の日本語名 */
    public String getLabel() {
        return label;
    }

    /** 特定原材料（表示義務あり）か */
    public boolean isMandatory() {
        return mandatory;
    }
}
