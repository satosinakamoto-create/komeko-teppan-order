package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店舗設定を設計（12 店舗設定 45:3279）くらいまで畳む（2026-09-07）。
 *
 * <p>実装には 26 項目あり、開くと 5,241px ありました。設計は 8 項目です。
 *
 * <p><b>いちばん守っているのは「畳んだだけで、消していない」ことです。</b><br>
 * どれも動きに効く設定で、消せるものは 1 つもありません。
 * とくに危ないのは、入力欄を画面から外すと
 * <b>保存のたびに初期値が書き込まれる</b>ことです
 * （保存処理はフォームから受け取った値をそのまま設定へ写すため）。
 * テンプレートのコメントにも前からその注意が書かれていました。
 * 「受付を止めたのに、保存したら勝手に再開していた」が起きます。
 *
 * <p>だからこのテストは<b>項目の数</b>を見ます。見た目ではなく、
 * 26 個ぜんぶが画面のどこかに残っていることを確かめます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("店舗設定の畳み方")
class SettingsLayoutTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path HTML =
            Path.of("src/main/resources/templates/admin/settings.html");

    /** 設計（45:3279）が描いている 8 つ。ここは畳まない。 */
    private static final List<String> 表に出す = List.of(
            "shopName", "tagline", "openTime", "lastOrderTime",
            "taxRatePercent", "tableChargePerGuest",
            "lateNightStartTime", "lateNightEndTime", "lateNightSurchargePercent");

    /** 一度決めたらふだん触らないもの。畳む。 */
    private static final List<String> 畳む = List.of(
            "pickupNotice", "closedMessage", "alwaysOpen", "closeTime",
            "orderNumberStart", "businessDayCutoverHour", "griddleCapacity",
            "monthlyRent", "taxStatus", "invoiceRegistrationNumber");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 畳んだ項目も画面に残っている（外すと保存で初期値に戻る）")
    void foldedFieldsAreStillOnThePage() throws Exception {
        String html = mockMvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ★ 入力欄を消すと、保存処理が初期値を書き込む。
        //   受付を止めたのに保存で勝手に再開する、が起きる
        for (String field : 畳む) {
            assertThat(html).as(field + " が画面から消えている").contains(field);
        }
        for (String field : 表に出す) {
            assertThat(html).as(field + " が画面から消えている").contains(field);
        }
    }

    @Test
    @DisplayName("★ 設計の 8 項目は畳んだ中に入れない")
    void everydayFieldsAreNotFolded() throws Exception {
        String html = Files.readString(HTML);
        int at = html.indexOf("<details class=\"foldout");
        assertThat(at).as("折りたたみが無い").isGreaterThan(-1);
        String folded = html.substring(at);

        for (String field : 表に出す) {
            assertThat(folded).as(field + " が畳まれている（毎日触る項目）")
                    .doesNotContain("*{" + field + "}");
        }
    }

    @Test
    @DisplayName("★ 「注文を受け付ける」は畳まない（営業中に急いで押すのはこれだけ）")
    void theBrakeStaysVisible() throws Exception {
        String html = Files.readString(HTML);
        int at = html.indexOf("<details class=\"foldout");
        assertThat(html.substring(at)).as("非常ブレーキが畳まれている")
                .doesNotContain("*{acceptingOrders}");
        assertThat(html.substring(0, at)).as("非常ブレーキが画面から消えている")
                .contains("*{acceptingOrders}");
    }

    @Test
    @DisplayName("★ 一度決めたら触らないものは畳んである")
    void rarelyUsedFieldsAreFolded() throws Exception {
        String html = Files.readString(HTML);
        String folded = html.substring(html.indexOf("<details class=\"foldout"));
        for (String field : List.of("pickupNotice", "closedMessage", "alwaysOpen", "closeTime",
                "orderNumberStart", "businessDayCutoverHour", "griddleCapacity",
                "monthlyRent", "invoiceRegistrationNumber")) {
            assertThat(folded).as(field + " が畳まれていない").contains("*{" + field + "}");
        }
    }

    @Test
    @DisplayName("見出しは 1 つだけ（page-head と section-title が二重にならない）")
    void oneHeadingOnly() throws Exception {
        String html = Files.readString(HTML).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html.split("店舗設定</h1>", -1).length - 1)
                .as("「店舗設定」の見出しが 2 つ出ている").isEqualTo(1);
    }
}
