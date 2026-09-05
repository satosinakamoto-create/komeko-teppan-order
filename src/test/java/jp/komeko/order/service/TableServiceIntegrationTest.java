package jp.komeko.order.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderLine;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.SettlementMethod;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TableService}（卓と伝票）の結合テスト。
 *
 * <p><b>このテストが守っているもの＝「1 卓 1 伝票」という大前提</b><br>
 * イートインの会計は「卓ごとに 1 枚の伝票」で成り立っています。
 * ここが崩れると、次のような事故が起きます。
 * <ul>
 *   <li>伝票が 2 枚できる → 片方だけ会計して、もう片方が取りっぱぐれる</li>
 *   <li>会計後も注文できる → 締めたあとの注文が誰にも請求されない</li>
 *   <li>人数を直しても金額が変わらない → テーブルチャージの請求漏れ</li>
 * </ul>
 *
 * <p><b>アノテーションの意味</b>
 * <ul>
 *   <li>{@code @SpringBootTest} … アプリ本体と同じ形で Spring を起動する</li>
 *   <li>{@code @ActiveProfiles("test")} … メモリ上の H2 を使う（application-test.yml）</li>
 *   <li>{@code @Transactional} … 各テストの終わりに DB の変更を巻き戻す。
 *       テストの実行順に左右されず、毎回まっさらから始められる</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("卓と伝票のサービス（DBあり）")
class TableServiceIntegrationTest {

    @Autowired
    private TableService tableService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private CartService cartService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private TableSessionRepository sessionRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 「読み込み済みかどうか」を試すために、いったん DB へ書き出して
     * 持っている写しを捨てる（flush / clear）のに使う。
     */
    @PersistenceContext
    private EntityManager entityManager;

    private ShopSetting setting;
    private DiningTable table;
    private MenuItem okonomiyaki;

    @BeforeEach
    void setUp() {
        // ── 採番カウンタだけは「別トランザクションで」消す ─────────────
        // OrderNumberService#next は Propagation.REQUIRES_NEW で採番してすぐコミットするため、
        // テストの @Transactional によるロールバックの対象外です。
        // 前のテストが払い出した番号が残るので、こちらも独立したトランザクションで消します。
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.executeWithoutResult(status -> dailyCounterRepository.deleteAllInBatch());

        // ── 何時に実行しても同じ結果になる設定にそろえる ───────────────
        // 実店舗の設定（17:30〜翌1:30）のままだと、昼にテストを走らせたときだけ
        // 「営業時間外」で注文が弾かれて落ちる、という不安定なテストになってしまう。
        setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);                  // 00:00
        // LocalTime.MAX は DB の TIME 型で丸められることがあるので秒までにしておく
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));  // 実質いつでも受付可
        setting.setBusinessDayCutoverHour(0);                // 営業日＝暦の日付
        setting.setOrderNumberStart(101);
        setting.setTaxRatePercent(10);                       // 酒類を扱う店なので 10%
        setting.setTableChargePerGuest(450);
        // 深夜料金は「実行した時刻」で自動判定されるため、金額の検証がぶれてしまう。
        // このクラスではチャージと小計の検証に集中したいので 0% にしておく。
        setting.setLateNightSurchargePercent(0);

        // ── 卓とメニューを用意する ─────────────────────────────
        // application-test.yml で seed-on-startup: false なので、
        // 卓もメニューもこのテストが自分で作る（何があるかを自分で把握できる状態にする）。
        table = tableService.createTable("1番テーブル", 4, 10);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem item = new MenuItem(category, "肉玉米粉そば", 1180);
        item.setCookMinutes(12);
        okonomiyaki = menuItemRepository.save(item);
    }

    /** 商品を 1 品だけ入れた新しいカートを作る。 */
    private Cart cartOf(MenuItem item, int quantity) {
        // Cart は @SessionScope の Bean なので、HTTP リクエストが無いテストでは DI できない。
        // placeOrder はカートを引数で受け取る設計なので、素直に new して渡す。
        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), quantity);
        return cart;
    }

    @Nested
    @DisplayName("伝票を開く（ご案内）")
    class OpenSession {

        @Test
        @DisplayName("同じ卓で2回開こうとしても、伝票は増えず同じものが返る")
        void openingTwiceReturnsTheSameSession() {
            // ★このクラスでいちばん大事なテスト★
            // 同じ卓のお客さんが 2 人とも QR を読む、というのは毎回起きること。
            // 人数ぶん伝票ができてしまうと、片方だけ会計して残りが取りっぱぐれになる。
            TableSession first = tableService.openSession(table.getId(), 2);
            TableSession second = tableService.openSession(table.getId(), 2);

            assertThat(second.getId()).isEqualTo(first.getId());
            // @Transactional で毎回巻き戻るので、DB にある伝票はこのテストが作った 1 件だけ
            assertThat(sessionRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("2回目に違う人数で開くと、伝票は増やさず人数だけ更新する")
        void openingAgainUpdatesGuestCount() {
            // あとから来たお連れさまが「4名」で読み直した、という状況。
            // 伝票は 1 枚のまま、人数だけ新しい申告に合わせる。
            TableSession first = tableService.openSession(table.getId(), 2);

            TableSession second = tableService.openSession(table.getId(), 4);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getGuestCount()).isEqualTo(4);
            assertThat(second.getTableChargeAmount()).isEqualTo(1800);   // 450 × 4名
        }

        @Test
        @DisplayName("お客さま側からの申告では、開いている伝票の人数が減らない")
        void customerCannotLowerGuestCountOfAnOpenBill() {
            // ★取りっぱぐれを防ぐテスト★
            // /t/{token}/start は認証なしなので、遅れて QR を読んだ 1 人の古い画面から
            // 「1名」が届く。6 名でご案内した卓がここで 1 名に化けると、
            // テーブルチャージが ¥2,700 → ¥450 に減り、会計まで誰も気づけない。
            TableSession bill = tableService.openSession(table.getId(), 6);
            assertThat(bill.getTableChargeAmount()).isEqualTo(2700);   // 450 × 6名

            TableSession afterCustomerPost = tableService.openSession(
                    table.getId(), 1, TableService.GuestCountSource.CUSTOMER);

            assertThat(afterCustomerPost.getId()).isEqualTo(bill.getId());
            assertThat(afterCustomerPost.getGuestCount()).isEqualTo(6);
            assertThat(afterCustomerPost.getTableChargeAmount()).isEqualTo(2700);
        }

        @Test
        @DisplayName("お客さま側からでも、人数が増える方向は反映される")
        void customerCanRaiseGuestCount() {
            // お連れさまが後から合流して読み直す、というごく普通の流れ。
            // 増える方向は取りっぱぐれにならず、間違っていてもお客さまが伝票で気づける。
            tableService.openSession(table.getId(), 2);

            TableSession joined = tableService.openSession(
                    table.getId(), 4, TableService.GuestCountSource.CUSTOMER);

            assertThat(joined.getGuestCount()).isEqualTo(4);
            assertThat(joined.getTableChargeAmount()).isEqualTo(1800);   // 450 × 4名
        }

        @Test
        @DisplayName("スタッフ側（ホール画面）からは、これまでどおり人数を減らせる")
        void staffCanStillLowerGuestCount() {
            // 人数を下げる判断は「卓を見ているスタッフ」に限定する、というのが上の 2 件の趣旨。
            // その受け皿がここ。塞ぎすぎて直せなくなっては本末転倒になる。
            tableService.openSession(table.getId(), 6);

            TableSession corrected = tableService.openSession(table.getId(), 2);

            assertThat(corrected.getGuestCount()).isEqualTo(2);
            assertThat(corrected.getTableChargeAmount()).isEqualTo(900);   // 450 × 2名
        }

        @Test
        @DisplayName("開いた直後でも、テーブルチャージぶんの請求額が入っている")
        void chargeIsCalculatedOnOpen() {
            TableSession bill = tableService.openSession(table.getId(), 2);

            assertThat(bill.getStatus()).isEqualTo(SessionStatus.OPEN);
            assertThat(bill.getSubtotalAmount()).isZero();
            assertThat(bill.getTableChargeAmount()).isEqualTo(900);
            assertThat(bill.getTotalAmount()).isEqualTo(900);
        }

        @Test
        @DisplayName("伝票が開いていない卓に requireOpenSession すると受付を断られる")
        void requireOpenSessionFailsWhenNothingIsOpen() {
            // QR は読んだが「ご案内（人数選択）」を通っていない状態。
            // ここで注文を通すと、どの伝票に足すか決まらないまま注文が浮いてしまう。
            assertThatThrownBy(() -> tableService.requireOpenSession(table.getId()))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("QR");
        }
    }

    @Nested
    @DisplayName("お会計（伝票を締める）")
    class CloseSession {

        @Test
        @DisplayName("会計すると、その卓の「開いている伝票」は無くなる")
        void currentSessionIsEmptyAfterClosing() {
            // ここが空にならないと、次のお客さんが前の組の伝票に注文を足してしまう。
            TableSession bill = tableService.openSession(table.getId(), 2);

            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            assertThat(tableService.currentSession(table.getId())).isEmpty();
            assertThat(tableService.getSession(bill.getId()).getStatus())
                    .isEqualTo(SessionStatus.CLOSED);
        }

        @Test
        @DisplayName("会計額は 小計 + テーブルチャージ で、内消費税も入る")
        void closedAmountIncludesTableCharge() {
            TableSession bill = tableService.openSession(table.getId(), 2);
            orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            TableSession closed = tableService.closeSession(bill.getId(), false, "店長", "現金", SettlementMethod.CASH);

            assertThat(closed.getSubtotalAmount()).isEqualTo(1180);
            assertThat(closed.getTableChargeAmount()).isEqualTo(900);      // 450 × 2名
            assertThat(closed.getTotalAmount()).isEqualTo(2080);
            assertThat(closed.getTaxAmount()).isEqualTo(189);              // 2,080 × 10 ÷ 110
            assertThat(closed.getClosedBy()).isEqualTo("店長");
            assertThat(closed.getNote()).isEqualTo("現金");
        }

        @Test
        @DisplayName("会計済みの伝票に注文しようとすると OrderRejectedException になる")
        void cannotOrderIntoClosedSession() {
            // 「お会計しました → まだスマホの画面が残っていて注文ボタンを押した」は起きる。
            // ここを通してしまうと、誰にも請求されない注文が厨房に流れる。
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            Cart cart = cartOf(okonomiyaki, 1);
            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("お会計はすでに済んでいます");
        }

        @Test
        @DisplayName("二重に会計しようとすると弾かれる")
        void cannotCloseTwice() {
            // 会計ボタンの二度押しで金額が二重計上されないようにする。
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            assertThatThrownBy(() -> tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("すでに会計済み");
        }

        @Test
        @DisplayName("誤会計は reopen で開け直せる")
        void reopenBringsSessionBack() {
            // 別の卓を会計してしまった、というのは実際にある操作ミス。
            // 取り消せないと、お客さんを待たせたまま手作業で復旧することになる。
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            tableService.reopenSession(bill.getId(), "店長");

            // Optional の中身を取り出して比べる。map しておくと
            // 「空だった」ときも NoSuchElementException ではなく素直な失敗メッセージになる。
            assertThat(tableService.currentSession(table.getId()).map(TableSession::getId))
                    .contains(bill.getId());
        }

        @Test
        @DisplayName("お支払い方法を選ばないと締められない")
        void paymentMethodIsRequired() {
            // 「未選択なら現金」にすると、押し忘れたぶんが現金売上に化ける。
            // 閉店後に金庫を数えたとき、実際にはカードで受け取っているのに
            // 現金が足りないように見え、原因の分からない差額として残る。
            TableSession bill = tableService.openSession(table.getId(), 2);

            assertThatThrownBy(() -> tableService.closeSession(bill.getId(), false, "店長", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("お支払い方法");

            // 弾いたあとも伝票は開いたまま。選び直せば締められる。
            assertThat(tableService.getSession(bill.getId()).getStatus())
                    .isEqualTo(SessionStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("お会計待ち（追加注文を止める）")
    class Checkout {

        @Test
        @DisplayName("お会計待ちの卓は空席にならない（次のお客さまを二重に案内しない）")
        void closingTableIsStillOccupied() {
            // ここが空席に見えると、同じ卓に次の組を案内できてしまう。
            // 伝票が 2 つ立ち、テーブルチャージも二重にかかる。
            TableSession bill = tableService.openSession(table.getId(), 2);

            tableService.startCheckout(bill.getId());

            assertThat(tableService.currentSession(table.getId()).map(TableSession::getId))
                    .contains(bill.getId());
            assertThat(tableService.openSessions())
                    .extracting(TableSession::getId)
                    .contains(bill.getId());
        }

        @Test
        @DisplayName("お会計待ちの卓では追加注文が止まる（案内の文言も会計済みとは分ける）")
        void closingTableRejectsOrders() {
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.startCheckout(bill.getId());

            Cart cart = cartOf(okonomiyaki, 1);
            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("お会計の準備中");
        }

        @Test
        @DisplayName("お会計待ちの卓を QR で読み直しても、伝票は増えない")
        void rescanDoesNotCreateSecondBill() {
            // お客さまが待っている間にスマホを触るのはふつうのこと。
            // ここで新しい伝票ができると、テーブルチャージを二重にいただくことになる。
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.startCheckout(bill.getId());

            TableSession again = tableService.openSession(table.getId(), 2);

            assertThat(again.getId()).isEqualTo(bill.getId());
        }

        @Test
        @DisplayName("再開すると、また注文できるようになる")
        void resumeMakesTableOrderableAgain() {
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.startCheckout(bill.getId());

            tableService.resumeOrdering(bill.getId());

            orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            assertThat(tableService.getSession(bill.getId()).getSubtotalAmount()).isEqualTo(1180);
        }

        @Test
        @DisplayName("会計済みの伝票は、お会計待ちに戻せない")
        void closedBillCannotStartCheckout() {
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            assertThatThrownBy(() -> tableService.startCheckout(bill.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("すでに会計済み");
        }
    }

    @Nested
    @DisplayName("チャージをいただかない人数")
    class ChargeExempt {

        @Test
        @DisplayName("除外してもお客さまの人数は減らない（売上の客数を守る）")
        void exemptKeepsGuestCount() {
            // 人数を減らして帳尻を合わせると、客単価も席の回転も狂う。
            TableSession bill = tableService.openSession(table.getId(), 4);

            TableSession updated = tableService.changeChargeExemptCount(bill.getId(), 2);

            assertThat(updated.getGuestCount()).isEqualTo(4);
            assertThat(updated.getTableChargeAmount()).isEqualTo(900);   // 450 × 2
        }

        @Test
        @DisplayName("人数を減らすと、除外人数もその人数まで下がる")
        void exemptFollowsGuestCount() {
            // 4 名中 3 名を除外したあと「やっぱり 2 名だった」と直したとき、
            // 除外が 3 名のまま残るとチャージがマイナスになる。
            TableSession bill = tableService.openSession(table.getId(), 4);
            tableService.changeChargeExemptCount(bill.getId(), 3);

            TableSession updated = tableService.changeGuestCount(bill.getId(), 2);

            assertThat(updated.getChargeExemptCount()).isEqualTo(2);
            assertThat(updated.getTableChargeAmount()).isZero();
        }
    }

    @Nested
    @DisplayName("人数の変更")
    class ChangeGuestCount {

        @Test
        @DisplayName("人数を変えるとテーブルチャージも請求額も変わる")
        void guestCountChangesChargeAndTotal() {
            // お客さんの自己申告と実際が違うことは日常茶飯事。
            // ホール画面から直したときに金額が追従しないと、そのぶん請求漏れになる。
            TableSession bill = tableService.openSession(table.getId(), 2);
            orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            assertThat(tableService.getSession(bill.getId()).getTotalAmount()).isEqualTo(2080);

            TableSession updated = tableService.changeGuestCount(bill.getId(), 4);

            assertThat(updated.getGuestCount()).isEqualTo(4);
            assertThat(updated.getTableChargeAmount()).isEqualTo(1800);       // 450 × 4名
            assertThat(updated.getTotalAmount()).isEqualTo(1180 + 1800);
        }

        @Test
        @DisplayName("会計済みの伝票の人数は変更できない")
        void cannotChangeGuestCountAfterClosing() {
            // 締めたあとに金額が動くと、レジの現金と記録が合わなくなる。
            TableSession bill = tableService.openSession(table.getId(), 2);
            tableService.closeSession(bill.getId(), false, "店長", null, SettlementMethod.CASH);

            assertThatThrownBy(() -> tableService.changeGuestCount(bill.getId(), 4))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("QR トークンから卓を引く")
    class AccessToken {

        @Test
        @DisplayName("正しいトークンならその卓が返る")
        void findsTableByToken() {
            DiningTable found = tableService.getByAccessToken(table.getAccessToken());

            assertThat(found.getId()).isEqualTo(table.getId());
            assertThat(found.getName()).isEqualTo("1番テーブル");
        }

        @Test
        @DisplayName("存在しないトークンでは TableNotFoundException になる")
        void unknownTokenIsRejected() {
            // トークンは推測できないランダム文字列。当てずっぽうで叩かれても、
            // 「たまたま別の卓が開く」ことがあってはならない。
            assertThatThrownBy(() -> tableService.getByAccessToken("no-such-token-1234"))
                    .isInstanceOf(TableService.TableNotFoundException.class);
        }

        @Test
        @DisplayName("利用停止中（active=false）の卓は QR を読んでも注文できない")
        void inactiveTableIsRejected() {
            // 貸切・席の一時撤去などで止めた卓。QR は貼られたままなので、
            // サーバ側で断らないと、誰も見ていない席の注文が厨房に流れてしまう。
            tableService.updateTable(table.getId(), table.getName(), table.getCapacity(), 10, false);

            String token = table.getAccessToken();
            assertThatThrownBy(() -> tableService.getByAccessToken(token))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("ご利用いただけません");
        }

        @Test
        @DisplayName("QR を再発行すると、それまでのトークンは使えなくなる")
        void regeneratedTokenInvalidatesTheOldOne() {
            // 卓の QR 画像が外部に出回ってしまったときの緊急手段。
            // 古いトークンが生きていたら再発行の意味がない。
            String oldToken = table.getAccessToken();

            tableService.regenerateToken(table.getId());

            assertThatThrownBy(() -> tableService.getByAccessToken(oldToken))
                    .isInstanceOf(TableService.TableNotFoundException.class);
            assertThat(tableService.getById(table.getId()).getAccessToken()).isNotEqualTo(oldToken);
        }
    }

    @Nested
    @DisplayName("ホール画面用の一覧")
    class OpenSessions {

        @Test
        @DisplayName("いま開いている伝票だけが並ぶ（会計済みは消える）")
        void listsOnlyOpenSessions() {
            // ホール画面は「いま席にいる組」を見るための画面。
            // 会計済みが残っていると、どの卓が空いたのか分からなくなる。
            DiningTable second = tableService.createTable("2番テーブル", 4, 20);
            TableSession first = tableService.openSession(table.getId(), 2);
            TableSession other = tableService.openSession(second.getId(), 3);

            assertThat(tableService.openSessions())
                    .extracting(TableSession::getId)
                    .containsExactlyInAnyOrder(first.getId(), other.getId());

            tableService.closeSession(first.getId(), false, "店長", null, SettlementMethod.CASH);

            assertThat(tableService.openSessions())
                    .extracting(TableSession::getId)
                    .containsExactly(other.getId());
        }

        @Test
        @DisplayName("明細とオプションまで読み終えて返す（ホール画面が中身を出せる前提）")
        void readsLinesAndOptionsBeforeReturning() {
            // ★ /hall の「卓ごとの注文」が丸ごと依存しているテスト ★
            //
            // このアプリは open-in-view: false なので、画面を描く時点では DB 接続がない。
            // 読み終えていない関連にテンプレートから触ると LazyInitializationException になり、
            // 「注文が 1 件も無いときは動くのに、注文が入った瞬間に /hall が真っ白になる」
            // という、いちばん気づきにくい壊れ方をする。
            //
            // openSessions() は applyCurrentAmounts() 経由で hydrate() を呼び、
            // 伝票 → 注文 → 明細 → オプション まで読み終えてから返している。
            // 将来 EntityGraph や hydrate をいじったときに、ここで気づけるようにしておく。
            TableSession bill = tableService.openSession(table.getId(), 2);
            orderService.placeOrder(cartOf(okonomiyaki, 2), bill.getId(), null);

            // 画面が「新しく読み込む」状況に合わせる。
            // これをしないと、直前の placeOrder で読み込み済みになったインスタンスが
            // そのまま返ってきてしまい、hydrate を消してもテストが通ってしまう。
            entityManager.flush();
            entityManager.clear();

            TableSession listed = tableService.openSessions().get(0);

            // Hibernate.isInitialized は「読み込み済みか」を、中身に触らずに調べる。
            // 先に getLines() の中を覗くとその場で読み込まれてしまうので、判定を必ず先に行う。
            assertThat(Hibernate.isInitialized(listed.getOrders()))
                    .as("伝票にぶら下がる注文")
                    .isTrue();

            Order order = listed.getOrders().get(0);
            assertThat(Hibernate.isInitialized(order.getLines()))
                    .as("注文の明細（品名・数量・金額）")
                    .isTrue();

            OrderLine line = order.getLines().get(0);
            assertThat(Hibernate.isInitialized(line.getOptions()))
                    .as("明細に付いたオプション（トッピングなど）")
                    .isTrue();
        }
    }
}
