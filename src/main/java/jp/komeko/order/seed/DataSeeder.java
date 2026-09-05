package jp.komeko.order.seed;

import jp.komeko.order.config.AppProperties;
import jp.komeko.order.domain.*;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.ShopSettingRepository;
import jp.komeko.order.repository.StaffUserRepository;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.StaffUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 起動時に初期データを投入する。
 *
 * <p>{@link ApplicationRunner} を実装した Bean は、
 * アプリの起動が終わった直後に一度だけ {@link #run} が呼ばれます。
 * 「初期データを入れる」「起動時にチェックする」といった用途で便利です。
 *
 * <p><b>すでにデータがあるときは何もしません。</b>
 * 毎回上書きすると、実際に登録したメニューが消えてしまうためです。
 *
 * <hr>
 *
 * <h2>⚠ このメニューデータについて（必ず読むこと）</h2>
 *
 * <p>下記のメニューは、公式サイト（{@code https://www.komekototeppan.com/}）の
 * お品書きページ 3 種（鉄板料理／数量限定・一品料理／ドリンク）から書き起こしたものです。
 * <b>そのまま営業に使う前に、次の 4 点を店舗側で必ず確認してください。</b>
 *
 * <ol>
 *   <li><b>税抜・税込</b> —
 *       ドリンクページには「表示価格は税抜きです」と明記されています。
 *       料理ページには表記がありません。
 *       このシステムは<b>価格を税込で持つ</b>設計なので、
 *       税抜のまま登録すると請求額が不足します。
 *       管理画面で税込価格に直すか、運用ルールを決めてください。</li>
 *   <li><b>アレルゲン</b> —
 *       <b>あえて 1 件も登録していません。</b>
 *       食物アレルギーは命に関わるため、原材料の仕入元表示を確認したうえで
 *       店舗の責任で登録してください。未登録の商品は画面に
 *       「アレルギーは店舗へお尋ねください」と表示されます。</li>
 *   <li><b>テーブルチャージ・深夜料金</b> —
 *       「終日テーブルチャージ ¥450／23 時以降は深夜料金 10%」は
 *       <b>このシステムでは計算できません</b>（伝票単位の加算に未対応）。
 *       会計時に店頭で加算してください。</li>
 *   <li><b>酒類の年齢確認</b> —
 *       スマホからの自己注文では年齢確認ができません。
 *       提供時に必ず店頭で確認してください。</li>
 * </ol>
 *
 * <p>調理時間（{@code cookMinutes}）は待ち時間の目安を出すためだけの値で、
 * こちらで仮に置いたものです。実際の提供スピードに合わせて管理画面で調整してください。
 *
 * <hr>
 *
 * <h2>{@code @Order(1)} を付けている理由</h2>
 *
 * <p>{@link ApplicationRunner} が複数あるとき、<b>Spring は実行順を保証しません。</b>
 * どちらにも順序を指定しないと両方が「最も低い優先度」として同じ扱いになり、
 * 最終的な順番は Bean が見つかった順、つまり<b>クラスパスを走査した順</b>で決まります。
 * これは環境によって変わりうる値です。
 *
 * <p>実際に事故が起きました。手元ではこちらが先に走っていたのに、
 * Render のコンテナでは {@link DemoDataSeeder} が先に走り、
 * 卓もメニューもまだ無い状態でデモデータを作ろうとして
 * 「0 卓ぶんの伝票を作成」となり、公開デモの厨房ボードが空になりました。
 * 例外は出ません。ログを順番に読むまで気付けませんでした。
 *
 * <p>このクラスは<b>土台</b>（卓・メニュー・初期アカウント）を作ります。
 * {@link DemoDataSeeder} はその土台の上に伝票を積むので、必ずこちらが先です。
 * <b>暗黙の順序に頼っている箇所は、いつか環境が変わった日に壊れます。</b>
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppProperties properties;
    private final CategoryRepository categoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final StaffUserRepository staffUserRepository;
    private final ShopSettingRepository shopSettingRepository;
    private final DiningTableRepository diningTableRepository;
    private final StaffUserService staffUserService;
    private final ShopSettingService shopSettingService;

    /** 作った商品をためておく箱（最後にまとめて保存する）。 */
    private final List<MenuItem> created = new ArrayList<>();

    public DataSeeder(AppProperties properties,
                      CategoryRepository categoryRepository,
                      MenuItemRepository menuItemRepository,
                      StaffUserRepository staffUserRepository,
                      ShopSettingRepository shopSettingRepository,
                      DiningTableRepository diningTableRepository,
                      StaffUserService staffUserService,
                      ShopSettingService shopSettingService) {
        this.properties = properties;
        this.categoryRepository = categoryRepository;
        this.menuItemRepository = menuItemRepository;
        this.staffUserRepository = staffUserRepository;
        this.shopSettingRepository = shopSettingRepository;
        this.diningTableRepository = diningTableRepository;
        this.staffUserService = staffUserService;
        this.shopSettingService = shopSettingService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean firstRun = shopSettingRepository.count() == 0;
        ShopSetting setting = shopSettingService.current();
        if (firstRun) {
            applyRealShopDefaults(setting);
        }

        seedAdmin();

        if (!properties.seedOnStartup()) {
            return;
        }
        seedTables();

        if (categoryRepository.count() > 0) {
            backfillGroupNames();
            log.info("メニューは登録済みのため、サンプル投入をスキップしました");
            return;
        }
        seedMenu();
    }

    /**
     * すでに登録済みのカテゴリに、大カテゴリ（タブ名）を一度だけ埋める。
     *
     * <p>大カテゴリは 2026-08-19 に追加した項目です。
     * それ以前から動いている DB のカテゴリは空のままなので、
     * 放っておくと 14 個のカテゴリが 14 本のタブになり、
     * せっかくまとめた意味がなくなります。そこで初回起動時に補います。
     *
     * <p><b>すでに値が入っているものには触りません。</b>
     * 店長が管理画面で決めた分類を、起動のたびに書き戻してしまうと
     * 「直したのに元に戻る」という最悪の挙動になります。
     *
     * <p>本来こういうデータの移行は Flyway のようなマイグレーションツールの仕事です
     * （docs/仕様.md「今後の課題」参照）。導入するまでの暫定措置として、
     * <b>やることが一度きりで、やり直しても結果が変わらない</b>形に留めています。
     */
    private void backfillGroupNames() {
        List<Category> targets = categoryRepository.findAll().stream()
                .filter(c -> c.getGroupName() == null)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        for (Category c : targets) {
            c.setGroupName(groupNameFor(c.getSortOrder()));
        }
        categoryRepository.saveAll(targets);
        log.info("既存カテゴリ {} 件に大カテゴリ（メニュー画面のタブ名）を設定しました。"
                + "変更したいときは 管理画面 → カテゴリ から", targets.size());
    }

    /**
     * 卓（テーブル）の初期データ。
     *
     * <p>実際の席数・席名は店舗ごとに違うので、あくまで<b>ひな形</b>です。
     * 管理画面 → 卓・QR から、実際の店内配置に合わせて作り直してください。
     * 卓を作り直すと QR も変わるので、印刷し直しが必要です。
     */
    private void seedTables() {
        if (diningTableRepository.count() > 0) {
            return;
        }
        int order = 0;
        for (int i = 1; i <= 6; i++) {
            order += 10;
            diningTableRepository.save(new DiningTable("カウンター" + i, 1, order));
        }
        for (int i = 1; i <= 4; i++) {
            order += 10;
            diningTableRepository.save(new DiningTable("テーブル" + i, 4, order));
        }
        log.info("卓を 10 席ぶん作成しました（管理画面から店内配置に合わせて調整してください）");
    }

    // ========================================================================
    //  店舗設定
    // ========================================================================

    /**
     * 実店舗（学芸大学「米粉と鉄板」）の情報を初期値として入れる。
     *
     * <p>営業時間が 17:30〜26:00（翌 2:00）と<b>日付をまたぐ</b>のがこの店の特徴です。
     * {@code ShopSetting#isOrderAcceptable} は日をまたぐ営業時間に対応しています。
     * あわせて営業日の切り替えを 5 時にしているので、
     * 深夜 1 時の注文は「前日の営業日」として集計されます。
     */
    private void applyRealShopDefaults(ShopSetting setting) {
        setting.setShopName("米粉と鉄板");
        setting.setTagline("グルテンフリーの米粉生地で焼き上げる鉄板料理");

        setting.setOpenTime(LocalTime.of(17, 30));
        setting.setLastOrderTime(LocalTime.of(1, 30));   // 翌 1:30
        setting.setCloseTime(LocalTime.of(2, 0));        // 翌 2:00（＝26:00）
        setting.setBusinessDayCutoverHour(5);

        // 鉄板で同時に焼ける品数。待ち時間の目安の計算にだけ使う値なので、
        // 実際のオペレーションに合わせて管理画面から調整してください。
        setting.setGriddleCapacity(6);

        // ⚠ 酒類を扱う店なので軽減税率(8%)は適用されません。
        //    店内飲食・酒類ともに 10% です。
        setting.setTaxRatePercent(10);

        setting.setOrderNumberStart(101);

        // 公式サイト記載の「終日テーブルチャージ ¥450／23時以降は深夜料金 10%」
        setting.setTableChargePerGuest(450);
        setting.setLateNightStartTime(LocalTime.of(23, 0));
        // 深夜の終わり。閉店（翌 2:00）より後の 5:00 にしてあるので、
        // 実質「23:00 以降のお会計はすべて深夜料金」になる。
        // 閉店までに限定したければ 2:00 にする。
        setting.setLateNightEndTime(LocalTime.of(5, 0));
        setting.setLateNightSurchargePercent(10);

        // 家賃（月額）。★ これは実額ではなく見積もりです。
        //
        //   分かっているのは「同じ広さの 1 階が ¥379,900」ということだけ。
        //   店舗物件では 2 階は 1 階の 6〜7 割が相場なので、
        //     ¥379,900 × 0.6 = ¥227,940
        //     ¥379,900 × 0.7 = ¥265,930
        //   の中を取って ¥250,000 としています。
        //   26.5㎡（約 8 坪）だと坪単価およそ ¥31,000 で、
        //   学芸大学駅から徒歩 5 分の 2 階としては妥当な水準です。
        //
        //   実額が分かったら、管理画面の「店舗設定 → 固定費」から差し替えてください。
        //   売上画面の「売上の配分」で賃貸の実績として使われる数字なので、
        //   ここがずれていると FL 比率の判断もずれます。
        setting.setMonthlyRent(250_000);

        setting.setPickupNotice(
                "お会計はお席の伝票にまとめてお付けします。ご退店時にレジまでお願いします。"
                        + "酒類は20歳未満の方へはご提供できません（店頭で年齢を確認させていただきます）。");
        setting.setClosedMessage("ただいま大変混み合っております。恐れ入りますがスタッフへお声がけください。");
        setting.touch();

        log.warn("""

                ============================================================
                 店舗設定に実店舗の初期値を入れました。
                   営業時間        17:30 〜 翌2:00（ラストオーダー 翌1:30）
                   消費税率        10%（酒類を扱うため軽減税率の対象外）
                   テーブルチャージ ¥450 / 人
                   深夜料金        23:00 〜 5:00 は 10%
                 ※土日は 16:00 開店ですが、曜日別の営業時間には未対応です。
                   管理画面 → 店舗設定 から必要に応じて変更してください。
                 ※時間を気にせず動作確認したいときは、店舗設定の
                   「24 時間受付」を ON にしてください。
                ============================================================
                """);
    }

    // ========================================================================
    //  スタッフ
    // ========================================================================

    /**
     * 管理者が 1 人もいなければ初期管理者を作る。
     *
     * <p>パスワードが設定されていなければ<b>ランダムに生成</b>し、ログに 1 回だけ出します。
     * 設定ファイルに固定パスワードを書かないためです。
     * Git にコミットした秘密は履歴に残り続け、あとから消すのは非常に大変なので、
     * 最初から「書かない」のがいちばん確実です。
     */
    private void seedAdmin() {
        if (staffUserRepository.count() > 0) {
            return;
        }
        String username = properties.initialAdminUsername();
        String password = properties.initialAdminPassword();
        boolean generated = false;
        if (password == null || password.isBlank()) {
            password = generatePassword();
            generated = true;
        }
        staffUserService.create(username, password, "店長", StaffRole.ADMIN);
        staffUserService.create("kitchen", password, "厨房スタッフ", StaffRole.STAFF);

        log.warn("""

                ============================================================
                 初期アカウントを作成しました。{}
                   管理者   : {} / {}
                   スタッフ : kitchen / {}

                 ★ この表示は初回起動のときだけです。いま控えてください。
                 ★ ログイン後、管理画面 → スタッフ からパスワードを変更してください。
                ============================================================
                """,
                generated ? "（パスワードは自動生成しました）" : "",
                username, password, password);
    }

    /**
     * 初期パスワードをランダムに作る。
     *
     * <p>{@link SecureRandom} は暗号用の乱数生成器です。
     * ふつうの {@code Math.random()} や {@code new Random()} は
     * 「次に何が出るか」が計算で当てられてしまうため、
     * <b>パスワードや鍵の生成には絶対に使いません</b>。
     *
     * <p>紛らわしい文字（0/O、1/l/I）は、口頭やメモで伝えるときの
     * 読み間違いを防ぐためにあらかじめ除いています。
     */
    private String generatePassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ========================================================================
    //  メニュー（公式サイトのお品書きより）
    // ========================================================================

    private void seedMenu() {
        log.info("公式サイトのお品書きをもとにメニューを投入します");

        seedOkonomiyaki();
        seedTakoyaki();
        seedTeppanOtsumami();
        seedTeppanNoodles();
        seedLimitedTeppan();
        seedSideDishes();
        seedDessert();
        seedBeerAndSour();
        seedCraftGin();
        seedWhisky();
        seedShochu();
        seedMocktail();
        seedSoftDrink();
        seedSakeAndWine();

        menuItemRepository.saveAll(created);

        log.warn("""

                ============================================================
                 メニュー {} 品を投入しました（公式サイトのお品書きより）。
                 営業に使う前に必ず確認してください:
                   1. 価格の税抜/税込  … ドリンクは「税抜」と明記されています
                   2. アレルゲン       … 意図的に未登録です。店舗で登録してください
                   3. テーブルチャージ ¥450 / 深夜料金 10% は本システムでは計算しません
                   4. 「時価」の商品は売り切れ状態で登録しています。
                      当日価格を入れてから販売開始してください
                ============================================================
                """, created.size());
    }

    /**
     * 商品に写真を付ける。
     *
     * <p><b>なぜ {@code static/images/menu/} に置くのか</b><br>
     * 管理画面からアップロードした画像は {@code app.upload-dir}（{@code ./data/uploads}）に
     * 保存されます。実店舗ではそれで正しいのですが、公開デモは無料ホスティングで動いており、
     * <b>再起動のたびにディスクごと作り直されます</b>。
     * アップロード先に置いた写真は次の起動で消え、また文字のプレースホルダに戻ります。
     *
     * <p>そこで、最初から入れておく写真だけは <b>jar の中に焼き込む</b>（静的ファイルにする）
     * ことにしました。消えようがありませんし、
     * 内容ハッシュ付きの長期キャッシュ（{@code application.yml} の
     * {@code spring.web.resources}）がそのまま効くので、2 回目以降は取りに行きません。
     *
     * <p>写真は公式サイト（{@code komekototeppan}）の撮影データから、
     * 幅 800px・4:3・JPEG 品質 82 に落として使っています（1 枚 50〜90KB）。
     * 画面側は一覧・詳細とも {@code object-fit: cover} なので、
     * 4:3 で持っておけば正方形のサムネイルにも破綻なく収まります。
     *
     * <p><b>全品には付けていません。</b>
     * 最初に目に入るカテゴリの主力だけで、印象はほぼ決まります。
     * 残りは店舗が管理画面から順に追加していく前提です。
     */
    private static MenuItem photo(MenuItem item, String fileName) {
        item.setImagePath("/images/menu/" + fileName);
        return item;
    }

    /** 広島風お好み焼き。全品「米粉そば ⇔ 米粉うどん」を無料で変更できる。 */
    private void seedOkonomiyaki() {
        Category c = category("広島風お好み焼き", 10);
        Menu m = new Menu(c, 12);

        MenuItem nikutama = photo(m.recommend("肉玉米粉そば", 1180, "定番。豚肉と卵、米粉そば入り。"),
                "okonomiyaki.jpg");
        MenuItem negi = m.add("ねぎたっぷり米粉そば", 1380, "青ねぎをたっぷりのせて。");
        MenuItem geso = m.add("肉玉大葉げそ米粉そば", 1480, "大葉とげその食感がアクセント。");
        MenuItem kaki = m.add("牡蠣と豚肉米粉そば", 1680, "季節の牡蠣を鉄板でふっくらと。");
        MenuItem kaisen = m.recommend("海鮮スペシャル", 1780, "海の幸をたっぷり贅沢に。");

        // 「※全品、米粉そばを米粉うどんへ無料変更できます」をオプションとして表現する。
        // 追加料金 0 円・必ず 1 つ選ぶ（min=1, max=1）＝ ラジオボタンとして描画される。
        for (MenuItem item : List.of(nikutama, negi, geso, kaki, kaisen)) {
            optionGroup(item, "麺の変更", 1, 1,
                    choice("米粉そば", 0, true),
                    choice("米粉うどん", 0, false));
        }
    }

    /**
     * 選べる米粉たこ焼き。
     *
     * <p>この店の看板メニュー。個数ごとに「選べる味の数」が違うので、
     * オプショングループの {@code minSelect}/{@code maxSelect} でそのまま表現できます。
     * 4 個なら 2 種類ちょうど、8 個以上なら 4 種類ちょうど選ぶ、という具合です。
     */
    private void seedTakoyaki() {
        Category c = category("選べる米粉たこ焼き", 20);
        Menu m = new Menu(c, 10);

        MenuItem t4 = m.recommend("たこ焼 4個", 600, "2種類のお味をお選びいただけます。");
        // 8 個の写真は「たこ焼 8 個＋薬味 4 皿」がそのまま写っているものを選んでいる。
        // 説明文の「4種類のお味をお選びいただけます」を、文字ではなく絵で伝えられる。
        MenuItem t8 = photo(m.recommend("たこ焼 8個", 1190, "4種類のお味をお選びいただけます。"),
                "takoyaki-plate.jpg");
        MenuItem t12 = m.add("たこ焼 12個", 1790, "4種類のお味をお選びいただけます。");
        MenuItem t16 = m.add("たこ焼 16個", 2390, "4種類のお味をお選びいただけます。");
        // 20 個は鉄板いっぱいに焼いている写真。量の多さが伝わる
        MenuItem t20 = photo(m.add("たこ焼 20個", 2990, "4種類のお味をお選びいただけます。"),
                "takoyaki-grill.jpg");

        addFlavors(t4, 2);
        for (MenuItem item : List.of(t8, t12, t16, t20)) {
            addFlavors(item, 4);
        }
    }

    /** たこ焼きの味 12 種類を、ちょうど {@code count} 種類選ぶオプションとして付ける。 */
    private void addFlavors(MenuItem item, int count) {
        optionGroup(item, "お味（%d種類お選びください）".formatted(count), count, count,
                choice("ソース", 0, false),
                choice("ごま油＆塩", 0, false),
                choice("出汁醤油", 0, false),
                choice("辣油七味", 0, false),
                choice("ねぎポン酢", 0, false),
                choice("ピリ辛出汁", 0, false),
                choice("ねぎマヨ", 0, false),
                choice("トリュフ塩", 0, false),
                choice("七味", 0, false),
                choice("醤油", 0, false),
                choice("マヨ七味", 0, false),
                choice("黒胡椒", 0, false));
    }

    private void seedTeppanOtsumami() {
        Menu m = new Menu(category("鉄板おつまみ", 30), 8);
        m.add("殻付きホタテバター醤油（北海道産）", 640, null);
        m.add("国産鶏皮にんにく醤油焼", 680, null);
        m.add("鉄板わかめ焼き（北海道産）", 770, null);
        m.add("鶏せせりとねぎの黒胡椒焼", 880, null);
        m.add("鉄板チョリソー五本", 880, null);
        photo(m.add("たこときのこの塩たれ焼", 880, null), "tako-kinoko.jpg");
        m.add("国産砂肝の鉄板塩たれ焼", 880, null);
        m.add("国産豚たん塩たれ焼", 880, null);
        m.add("きのことチーズの鉄板オープンオムレツ", 1120, null);
        photo(m.add("黒毛和牛上ホルモン焼", 1230, null), "hormone.jpg");
        photo(m.recommend("濃厚！国産豚ぺい焼", 1430, "当店自慢のとん平焼き。"), "tonpei.jpg");
        m.add("鉄板自家製ジャークチキン", 1890, null);
    }

    private void seedTeppanNoodles() {
        Menu m = new Menu(category("鉄板麺", 40), 10);
        photo(m.recommend("米粉麺焼きそば（ソース）", 1190, "グルテンフリーの米粉麺を使用。"),
                "yakisoba.jpg");
        m.add("米粉焼きうどん（出汁醤油）", 1190, null);
        m.add("国産上ホルモン焼きそば", 1790, null);
    }

    private void seedLimitedTeppan() {
        Menu m = new Menu(category("数量限定鉄板焼き", 50), 15);

        // 「数量限定」カテゴリなので、残数管理の実例として初期値を入れておく。
        // 数字は仮。実際の仕込み数は開店前に 厨房画面 → 品切れ・残数管理 で設定する。
        // 残数は注文で自動的に減り、0 になると各卓のメニューで売り切れ表示になる。
        MenuItem pork = m.add("国産豚ロースステーキ", 2180, null);
        pork.setStockRemaining(8);

        m.askStaff("国産牛サーロインステーキ", "時価。仕入れ状況により価格が変わります。");
        m.askStaff("国産牛赤身ステーキ", "時価。仕入れ状況により価格が変わります。");

        MenuItem tako = m.add("鉄板たこ足塊ステーキ", 2180, null);
        tako.setStockRemaining(5);
    }

    private void seedSideDishes() {
        Menu m = new Menu(category("一品料理", 60), 5);
        photo(m.add("たこのねぎまみれ", 680, null), "tako-negi.jpg");
        photo(m.add("冷やしトマト", 600, null), "hiyashi-tomato.jpg");
        m.add("蘭王ゆでねぎたま", 650, null);
        photo(m.recommend("自家製ポテサラ", 780, null), "potesara.jpg");
        m.add("ピリ辛豆板醤きゅうり", 580, null);
        m.add("さっぱりたこぽん", 680, null);
        m.add("本日の特製サラダ", 1200, null);
    }

    private void seedDessert() {
        Menu m = new Menu(category("甘味", 70), 3);
        m.add("本日のおすすめアイス", 480, null);
    }

    private void seedBeerAndSour() {
        Menu m = new Menu(category("ビール・サワー", 80), 2);
        m.add("サッポロ赤星（中瓶）", 700, null);
        // 公式サイトの素材。銘柄そのものの写真なので本番でも使える
        photo(m.add("オールフリー（ノンアルコールビール）", 600, null), "allfree.jpg");
        m.recommend("自家製レモンサワー", 850, "お店のおすすめ。");
        m.recommend("ジャスミンハイボール", 800, "お店のおすすめ。");
    }

    /**
     * クラフトジン。
     *
     * <p><b>全品に同じ「氷のグラス」の写真を当てています。</b>
     * 16 種それぞれのボトル写真は持っていません。
     *
     * <p>持っている素材で代用しようとすると、ジョッキに他社（ビールメーカー）の
     * ロゴが入ったものしか無く、<b>クラフトジンの欄にビールのロゴが並ぶ</b>ことになります。
     * 銘柄を名指ししている商品に別の銘柄の写真を出すのは、
     * 「それっぽい」で済ませてよい範囲を越えます。
     *
     * <p>そこで<b>銘柄を写さない</b>共通の一枚にしました。
     * 産地は説明文（横浜・フランス・京都…）で伝わるので、
     * 写真は「氷のグラスで出てくる」ことだけを伝えれば足ります。
     */
    private void seedCraftGin() {
        Menu m = new Menu(category("クラフトジン", 90), 3);
        photo(m.add("No.8", 800, "横浜"), "ice-glass.jpg");
        photo(m.recommend("メリフェラジン", 1000, "フランス"), "ice-glass.jpg");
        photo(m.add("マーメイドピンク", 1000, "イングランド"), "ice-glass.jpg");
        photo(m.add("アルケミスト", 1200, "スペイン"), "ice-glass.jpg");
        photo(m.add("ストランジネイチャー", 1200, "ニュージーランド"), "ice-glass.jpg");
        photo(m.recommend("HOLON 金木犀", 1200, "東京・蔵前"), "ice-glass.jpg");
        photo(m.recommend("ENGINE", 900, "イタリア"), "ice-glass.jpg");
        photo(m.add("ローンウルフ", 1000, "スコットランド"), "ice-glass.jpg");
        photo(m.recommend("バスタブ", 955, "イタリア"), "ice-glass.jpg");
        photo(m.add("KOMASA 小みかん・ほうじ茶・苺", 900, "鹿児島"), "ice-glass.jpg");
        photo(m.add("EMPRESS1908", 900, "カナダ"), "ice-glass.jpg");
        photo(m.add("サイレントプール", 800, "イギリス"), "ice-glass.jpg");
        photo(m.add("Bobby's", 900, "オランダ"), "ice-glass.jpg");
        photo(m.add("Monkey47", 1200, "ドイツ"), "ice-glass.jpg");
        photo(m.recommend("五島ジン GOTOGIN", 1300, "五島列島"), "ice-glass.jpg");
        photo(m.recommend("季の美", 800, "京都"), "ice-glass.jpg");
    }

    private void seedWhisky() {
        Menu m = new Menu(category("ウィスキー", 100), 2);
        m.add("角ハイボール", 680, null);
        m.add("KIRIN 陸", 700, null);
        m.add("山崎", 1400, null);
        m.add("白州", 1400, null);
        m.add("響 Japanese Harmony", 1400, null);
        m.add("知多", 900, null);
        m.add("宮城峡", 990, null);
        m.add("余市", 990, null);
        m.add("ハイランドパーク 12Years", 1200, null);
        m.add("BOWMORE 12Years", 1100, null);
        m.add("タリスカー 10Years", 1000, null);
        m.add("Arran 10Years", 1200, null);
        m.add("グレンモーレンジィ 12Years", 1200, null);
        m.add("ニッカフロンティア", 700, null);
    }

    private void seedShochu() {
        Menu m = new Menu(category("焼酎・スパイス", 110), 2);
        m.recommend("カルダモン焼酎", 800, "お店のおすすめ。");
        m.recommend("AKAYANE 山椒", 900, "お店のおすすめ。");
        m.add("佐藤【黒・白・麦】", 900, null);
        m.add("百年の孤独", 900, null);
        m.add("魔王", 900, null);
        m.add("黒ウーロンハイ", 900, null);
        m.add("お茶割り各種【ジャスミン割・緑茶割】", 700, null);
    }

    private void seedMocktail() {
        Menu m = new Menu(category("モクテル（ノンアルカクテル）", 120), 3);
        m.add("特製ノンアルレモンサワー", 800, null);
        m.add("ノンアルジントニック", 800, null);
        m.add("ノンアルシャンディガフ", 800, null);
    }

    private void seedSoftDrink() {
        Menu m = new Menu(category("ソフトドリンク", 130), 1);
        m.recommend("自家製レモンスカッシュ", 500, "お店のおすすめ。");
        m.add("コカ・コーラ", 500, null);
        m.add("ジンジャーエール", 500, null);
        m.add("自家製レモネード", 500, null);
        m.add("黒烏龍茶", 500, null);
        m.add("緑茶", 500, null);
        m.add("ジャスミンティー", 500, null);
        m.add("トニックウォーター", 500, null);
        m.add("オレンジジュース", 500, null);
        m.add("炭酸水", 500, null);
    }

    /** 日本酒とワインは固定の品書きが無く「おすすめをご用意」の運用。 */
    private void seedSakeAndWine() {
        Menu m = new Menu(category("日本酒・ワイン", 140), 3);
        m.askStaff("本日の日本酒（おまかせ）",
                "シーズンによりラインナップが異なります。鉄板焼きに合うものをご用意しています。");
        m.askStaff("グラスワイン（赤・白）",
                "鉄板焼きに合うワインをご用意しています。");
        m.askStaff("ボトルワイン",
                "各種ご用意がございます。スタッフにお尋ねください。");
    }

    // ========================================================================
    //  組み立てを短く書くためのヘルパー
    // ========================================================================

    private Category category(String name, int sortOrder) {
        Category c = new Category(name, sortOrder);
        c.setGroupName(groupNameFor(sortOrder));
        return categoryRepository.save(c);
    }

    /**
     * 並び順から大カテゴリ（メニュー画面のタブ名）を決める。
     *
     * <p>タブは横に並ぶので、多すぎると端まで探しに行けません。
     * 14 個のカテゴリを 4 つのタブにまとめています。
     *
     * <p><b>2026-08-27 に 5 つから 4 つへ減らしました。</b>
     * 「お好み焼き」タブの中身は「広島風お好み焼き」1 カテゴリだけ、
     * 「たこ焼き」タブの中身は「選べる米粉たこ焼き」1 カテゴリだけでした。
     * 子が 1 つしかないタブは、階層を 1 段増やしているのに選択肢を増やしていません。
     * お客さまはタブを押したあと、ほぼ同じ言葉の見出しをもう一度読むことになります。
     *
     * <p>幅の問題もありました。タブは横スクロールしますが
     * スクロールバーを消してあるため（app.css の .tabs）、
     * はみ出したタブは<b>存在に気づけません</b>。
     * 5 つだと 390px の画面に対して合計 507px で、右端が隠れていました。
     * 4 つ（粉もの／鉄板料理／一品料理／ドリンク）なら 385px で全部見えます。
     *
     * <p>ここで決めているのは<b>初期値だけ</b>です。
     * 開店後は管理画面（カテゴリ）から自由に変えられますし、
     * 並び順を変えてもこの割り当ては追随しません（DB に保存された値が正）。
     */
    private String groupNameFor(int sortOrder) {
        if (sortOrder < 30) {
            // 広島風お好み焼き / 選べる米粉たこ焼き。
            // どちらも米粉を溶いて鉄板で焼く品なので、お客さまから見ると同じ選択の中にある。
            // 店名の「米粉」がそのまま説明になる言葉として「粉もの」を選んだ。
            return "粉もの";
        }
        if (sortOrder < 60) {
            return "鉄板料理";      // 鉄板おつまみ / 鉄板麺 / 数量限定鉄板焼き
        }
        if (sortOrder < 80) {
            return "一品料理";      // 一品料理 / 甘味
        }
        return "ドリンク";          // ビール・サワー 〜 日本酒・ワイン
    }

    /**
     * 1 カテゴリ分の商品を、並び順を自動で振りながら作るための小さな道具。
     *
     * <p>同じような 3 行を何十回も書くより、こうして「作り方」をまとめたほうが
     * 追加・修正がラクになります。特別な技術ではなく、素直な内部クラスです。
     */
    private final class Menu {
        private final Category category;
        private final int cookMinutes;
        private int order = 0;

        private Menu(Category category, int cookMinutes) {
            this.category = category;
            this.cookMinutes = cookMinutes;
        }

        /** ふつうの商品。 */
        MenuItem add(String name, int price, String description) {
            return build(name, price, description, false, false);
        }

        /** おすすめバッジ付き（公式サイトで ★ が付いていたもの）。 */
        MenuItem recommend(String name, int price, String description) {
            return build(name, price, description, true, false);
        }

        /**
         * 「時価」「おまかせ」など、価格が日によって変わる商品。
         *
         * <p>価格 0 円のまま注文できてしまうと事故になるので、
         * <b>売り切れ状態で登録</b>します。
         * 当日の価格を管理画面で入れて「販売再開」にしてから提供してください。
         */
        MenuItem askStaff(String name, String description) {
            return build(name, 0, description, false, true);
        }

        private MenuItem build(String name, int price, String description,
                               boolean recommended, boolean soldOut) {
            MenuItem item = new MenuItem(category, name, price);
            item.setDescription(description);
            item.setCookMinutes(cookMinutes);
            item.setRecommended(recommended);
            item.setSoldOut(soldOut);
            order += 10;
            item.setSortOrder(order);
            // アレルゲンは意図的に未登録。クラス上部のコメントを参照。
            created.add(item);
            return item;
        }
    }

    private OptionChoice choice(String name, int extraPrice, boolean defaultSelected) {
        OptionChoice c = new OptionChoice(name, extraPrice, 0);
        c.setDefaultSelected(defaultSelected);
        return c;
    }

    private void optionGroup(MenuItem item, String groupName, int min, int max, OptionChoice... choices) {
        OptionGroup group = new OptionGroup(groupName, min, max, item.getOptionGroups().size() * 10);
        int order = 0;
        for (OptionChoice choice : choices) {
            choice.setSortOrder(order);
            order += 10;
            group.addChoice(choice);
        }
        item.addOptionGroup(group);
    }
}
