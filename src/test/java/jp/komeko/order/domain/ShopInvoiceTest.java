package jp.komeko.order.domain;

import jp.komeko.order.inventory.domain.RegistrationNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自分の店の「消費税の立場」と「インボイスの登録番号」のテスト。
 *
 * <p><b>このテストが守っているもの＝納税に関わる表示が嘘をつかないこと</b><br>
 * ここが崩れても、画面はエラーを出しません。もっともらしい数字が並ぶだけです。
 * 気づくのは確定申告のとき、あるいは気づかないまま提出したあとです。
 *
 * <p>とくに危ないのが<b>未設定を「免税事業者」として扱ってしまうこと</b>。
 * まだ何も入力していない店に「消費税を納めなくてよい」と表示されるのは、
 * 単なる初期値ではなく<b>嘘</b>です。
 */
@DisplayName("消費税の立場とインボイス登録番号")
class ShopInvoiceTest {

    @Nested
    @DisplayName("初期状態")
    class Defaults {

        @Test
        @DisplayName("何も設定していない店は「未設定」であって「免税事業者」ではない")
        void startsUnsetNotExempt() {
            ShopSetting shop = new ShopSetting();

            assertThat(shop.getTaxStatus()).isEqualTo(TaxStatus.UNSET);
            assertThat(shop.getTaxStatus().isDecided()).isFalse();
            // 未設定のうちは「控除の話に意味がある」とは言わせない
            assertThat(shop.usesTaxDeduction()).isFalse();
            assertThat(shop.isInvoiceRegistered()).isFalse();
        }

        @Test
        @DisplayName("立場に null が来たら未設定に倒す（免税に倒さない）")
        void nullFallsBackToUnset() {
            // 画面から想定外の値が来たとき、免税に倒すと
            // 「消費税を納めなくてよい」という嘘が黙って表示される
            ShopSetting shop = new ShopSetting();
            shop.setTaxStatus(TaxStatus.TAXABLE);

            shop.setTaxStatus(null);

            assertThat(shop.getTaxStatus()).isEqualTo(TaxStatus.UNSET);
        }
    }

    @Nested
    @DisplayName("控除の話に意味があるか")
    class Deduction {

        @Test
        @DisplayName("課税事業者のときだけ、控除できる税額に意味がある")
        void onlyTaxableUsesDeduction() {
            // 税理士画面の断り書きは、この判定で出し分けている
            ShopSetting shop = new ShopSetting();

            shop.setTaxStatus(TaxStatus.TAXABLE);
            assertThat(shop.usesTaxDeduction()).isTrue();

            shop.setTaxStatus(TaxStatus.EXEMPT);
            assertThat(shop.usesTaxDeduction()).isFalse();
        }

        @Test
        @DisplayName("免税事業者は消費税の申告をしない")
        void exemptDoesNotFile() {
            assertThat(TaxStatus.EXEMPT.filesTaxReturn()).isFalse();
            assertThat(TaxStatus.TAXABLE.filesTaxReturn()).isTrue();
            assertThat(TaxStatus.UNSET.filesTaxReturn()).isFalse();
        }
    }

    @Nested
    @DisplayName("登録番号")
    class Number {

        @Test
        @DisplayName("番号を入れると、適格請求書を出せる店になる")
        void registeredWhenNumberPresent() {
            ShopSetting shop = new ShopSetting();

            shop.setInvoiceRegistrationNumber("T7000012050002");

            assertThat(shop.isInvoiceRegistered()).isTrue();
        }

        @Test
        @DisplayName("空白だけの入力は「持っていない」として扱う")
        void blankMeansNotRegistered() {
            // 画面で消したつもりが空白 1 文字だけ残る、はよくある。
            // そのまま持つと「登録番号あり」と判定され、
            // 領収書に空白が刷られることになる
            ShopSetting shop = new ShopSetting();

            shop.setInvoiceRegistrationNumber("   ");

            assertThat(shop.getInvoiceRegistrationNumber()).isNull();
            assertThat(shop.isInvoiceRegistered()).isFalse();
        }

        @Test
        @DisplayName("立場と番号は別々に持てる（課税だが未登録、があり得る）")
        void taxableWithoutNumberIsRepresentable() {
            // 真偽値 1 つにまとめると、この状態が表せなくなる。
            // 消費税は納めるが、こちらが出す領収書では相手が控除できない、という店
            ShopSetting shop = new ShopSetting();

            shop.setTaxStatus(TaxStatus.TAXABLE);

            assertThat(shop.usesTaxDeduction()).isTrue();
            assertThat(shop.isInvoiceRegistered()).isFalse();
        }

        @Test
        @DisplayName("仕入先の番号と同じ道具で検算できる（実装は 1 か所）")
        void sharesTheValidatorWithPurchases() {
            // 国税庁自身の法人番号。検算に通る既知の値
            String normalized = RegistrationNumber.normalize("T7000-0120-5000-2");

            assertThat(normalized).isEqualTo("T7000012050002");
            assertThat(RegistrationNumber.hasValidFormat(normalized)).isTrue();
            assertThat(RegistrationNumber.matchesCorporateCheckDigit(normalized)).isTrue();
        }
    }
}
