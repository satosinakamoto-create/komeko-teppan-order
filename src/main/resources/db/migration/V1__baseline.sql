-- ============================================================================
--  V1 : 土台のスキーマ（卓・伝票・注文・メニュー・スタッフ）
-- ============================================================================
--  ★ このファイルは 2026-09-05 に「あとから」書き起こしたものです。
--
--    ここにある 13 テーブルは、もともと Hibernate が開発中に
--    `ddl-auto: update` で作ったものでした。Flyway を入れたのは在庫機能
--    （V2）からで、それより前の土台は「もうある前提」で扱っていました。
--
--    そのため、**まっさらな PostgreSQL ではアプリが起動できませんでした。**
--    Flyway が V2 から流し、V4 が menu_item への外部キーを張ろうとして
--    そんなテーブルは無いと落ちる。仮に通っても `ddl-auto: validate` が
--    卓も伝票も見つけられずに止まります。
--    店のサーバーに新しい DB を立てた、その日に初めて分かる種類の詰まり方です。
--
--    中身は手書きではありません。BaselineDdlDumpTest がアプリを実際に
--    PostgreSQL につないで起動し、Hibernate に「このエンティティ群なら
--    こういうテーブルが要る」と言わせた出力を、人が整えたものです。
--    手で 13 テーブルぶん書くと、必ずどこかで型か NOT NULL を間違えます。
--
--  ★ すでに動いている DB には流れません
--
--    application-prod.yml に `baseline-on-migrate: true` と
--    `baseline-version: 1` が書いてあります。テーブルはあるのに Flyway の
--    履歴が無い DB は「バージョン 1 まで済んでいる」と記録され、V2 から続きます。
--    つまりこの V1 が実際に走るのは、**空の DB のときだけ**です。
--
--  ★ enum の列に CHECK を付けていません（V2 以降と同じ方針）
--
--    付けると、enum に値を 1 つ足すたびに ALTER を書かねばならず、
--    忘れると**本番でだけ**実行時に落ちます。しかも落ちるのは、
--    その値を初めて使った夜です。2026-09-05 に SessionStatus へ
--    CLOSING を足したときが、まさにその形でした。
--    値の正しさは Java の enum が保証するので、DB では varchar で受けます。
--    Hibernate の `validate` も CHECK までは見ません。
--
--  ★ この先ずっと守ってほしいこと
--
--    エンティティに列を足したら、必ず対応するマイグレーションも足すこと。
--    FreshDatabaseBootTest が、空の DB に V1 から順に流してから
--    Hibernate に validate させています。足し忘れるとそこで落ちます。
-- ----------------------------------------------------------------------------


-- ── メニュー ────────────────────────────────────────────────────────────────

CREATE TABLE category (
    id         BIGSERIAL   PRIMARY KEY,
    name       VARCHAR(40) NOT NULL,
    -- お客さま側のタブ名。空ならカテゴリ名がそのままタブになる
    group_name VARCHAR(20),
    sort_order INTEGER     NOT NULL,
    visible    BOOLEAN     NOT NULL
);

CREATE TABLE menu_item (
    id              BIGSERIAL    PRIMARY KEY,
    category_id     BIGINT       NOT NULL REFERENCES category (id),
    name            VARCHAR(60)  NOT NULL,
    description     VARCHAR(300),
    -- 金額は円の整数。小数は使わない（誤差が出る）
    price           INTEGER      NOT NULL CHECK (price >= 0),
    cook_minutes    INTEGER      NOT NULL CHECK (cook_minutes >= 0),
    image_path      VARCHAR(200),
    -- 数量限定の品の「今日の数」。限定でない品は NULL
    stock_remaining INTEGER,
    sold_out        BOOLEAN      NOT NULL,
    recommended     BOOLEAN      NOT NULL,
    visible         BOOLEAN      NOT NULL,
    sort_order      INTEGER      NOT NULL
);

-- アレルゲンは @ElementCollection。1 商品に複数行入る
CREATE TABLE menu_item_allergen (
    menu_item_id BIGINT      NOT NULL REFERENCES menu_item (id),
    allergen     VARCHAR(30)
);

CREATE TABLE option_group (
    id           BIGSERIAL   PRIMARY KEY,
    menu_item_id BIGINT      REFERENCES menu_item (id),
    name         VARCHAR(40) NOT NULL,
    min_select   INTEGER     NOT NULL CHECK (min_select >= 0),
    max_select   INTEGER     NOT NULL CHECK (max_select >= 1),
    sort_order   INTEGER     NOT NULL
);

CREATE TABLE option_choice (
    id               BIGSERIAL   PRIMARY KEY,
    option_group_id  BIGINT      REFERENCES option_group (id),
    name             VARCHAR(40) NOT NULL,
    -- 追加料金は 0 円以上。値引きを許すと単価が負になり、
    -- その卓の小計から他の品の代金が引かれる
    extra_price      INTEGER     NOT NULL,
    default_selected BOOLEAN     NOT NULL,
    sold_out         BOOLEAN     NOT NULL,
    sort_order       INTEGER     NOT NULL
);


-- ── 卓と伝票 ────────────────────────────────────────────────────────────────

CREATE TABLE dining_table (
    id           BIGSERIAL   PRIMARY KEY,
    name         VARCHAR(20) NOT NULL,
    capacity     INTEGER     NOT NULL CHECK (capacity >= 1),
    -- QR に入るのはこの token。連番 ID は URL に出さない
    access_token VARCHAR(36) NOT NULL UNIQUE,
    active       BOOLEAN     NOT NULL,
    sort_order   INTEGER     NOT NULL
);

-- 伝票。金額は「来店時点の設定をコピー」して持つ。
-- 店舗設定を変えても、開いている伝票の条件は変わらない。
CREATE TABLE table_session (
    id                           BIGSERIAL   PRIMARY KEY,
    dining_table_id              BIGINT      NOT NULL REFERENCES dining_table (id),
    business_date                DATE        NOT NULL,
    status                       VARCHAR(20) NOT NULL,
    guest_count                  INTEGER     NOT NULL,
    opened_at                    TIMESTAMP   NOT NULL,
    closed_at                    TIMESTAMP,
    closed_by                    VARCHAR(40),
    note                         VARCHAR(200),
    -- ここから下は来店時点のスナップショット
    tax_rate_percent             INTEGER     NOT NULL,
    table_charge_per_guest       INTEGER     NOT NULL,
    late_night_surcharge_percent INTEGER     NOT NULL,
    -- 計算結果（TableSession.recalculate の 1 箇所だけが書く）
    subtotal_amount              INTEGER     NOT NULL,
    table_charge_amount          INTEGER     NOT NULL,
    late_night_amount            INTEGER     NOT NULL,
    total_amount                 INTEGER     NOT NULL,
    tax_amount                   INTEGER     NOT NULL,
    late_night_applied           BOOLEAN     NOT NULL,
    -- スタッフが会計時にサービスで外した、という人の判断。
    -- 計算し直しても消えてはいけない
    late_night_waived            BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ホール画面は「開いている伝票」と「今日の伝票」しか読まない
CREATE INDEX idx_session_status        ON table_session (status);
CREATE INDEX idx_session_business_date ON table_session (business_date);


-- ── 注文 ────────────────────────────────────────────────────────────────────

-- テーブル名が orders なのは、ORDER が SQL の予約語のため
CREATE TABLE orders (
    id                     BIGSERIAL    PRIMARY KEY,
    session_id             BIGINT       NOT NULL REFERENCES table_session (id),
    business_date          DATE         NOT NULL,
    -- 営業日ごとの通し番号。同じ日に同じ番号は作れない
    order_number           INTEGER      NOT NULL,
    -- お客さまが自分の注文を見るための token。連番 ID は使わない
    public_token           VARCHAR(36)  NOT NULL UNIQUE,
    status                 VARCHAR(20)  NOT NULL,
    customer_name          VARCHAR(20),
    note                   VARCHAR(200),
    -- 注文時点の税率と金額のスナップショット。マスタは参照しない
    tax_rate_percent       INTEGER      NOT NULL,
    total_amount           INTEGER      NOT NULL,
    tax_amount             INTEGER      NOT NULL,
    estimated_cook_minutes INTEGER      NOT NULL,
    -- 打ち直しの救済。この注文だけ深夜料金の対象外にする
    late_night_exempt      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 時刻は事実の記録。人が書き換えられるようにしない
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    cooking_started_at     TIMESTAMP,
    ready_at               TIMESTAMP,
    completed_at           TIMESTAMP,
    canceled_at            TIMESTAMP,
    canceled_reason        VARCHAR(100),
    last_handled_by        VARCHAR(40),
    CONSTRAINT uk_orders_business_date_number UNIQUE (business_date, order_number)
);

-- 厨房ボードは状態で、注文履歴は営業日で引く
CREATE INDEX idx_orders_status        ON orders (status);
CREATE INDEX idx_orders_business_date ON orders (business_date);

-- 明細。商品名と価格を「注文時点の値」でコピーして持つので、
-- あとから商品を消しても伝票は当時のまま残る
CREATE TABLE order_line (
    id             BIGSERIAL   PRIMARY KEY,
    order_id       BIGINT      NOT NULL REFERENCES orders (id),
    -- 商品が削除されても明細は残るので NULL 可
    menu_item_id   BIGINT,
    menu_item_name VARCHAR(60) NOT NULL,
    base_price     INTEGER     NOT NULL,
    unit_price     INTEGER     NOT NULL,
    quantity       INTEGER     NOT NULL,
    line_total     INTEGER     NOT NULL,
    cook_minutes   INTEGER     NOT NULL
);

CREATE TABLE order_line_option (
    id            BIGSERIAL   PRIMARY KEY,
    order_line_id BIGINT      NOT NULL REFERENCES order_line (id),
    choice_id     BIGINT,
    choice_name   VARCHAR(40) NOT NULL,
    group_name    VARCHAR(40),
    extra_price   INTEGER     NOT NULL
);


-- ── 設定・採番・スタッフ ────────────────────────────────────────────────────

-- 設定は 1 行だけ。id は固定値（採番しない）
CREATE TABLE shop_setting (
    id                           BIGINT       PRIMARY KEY,
    shop_name                    VARCHAR(40)  NOT NULL,
    tagline                      VARCHAR(60),
    closed_message               VARCHAR(100),
    pickup_notice                VARCHAR(200),
    open_time                    TIME         NOT NULL,
    close_time                   TIME         NOT NULL,
    last_order_time              TIME         NOT NULL,
    -- 営業日の切り替え時刻。深夜 2 時の注文を前日の売上に入れるため
    business_day_cutover_hour    INTEGER      NOT NULL
                                 CHECK (business_day_cutover_hour BETWEEN 0 AND 23),
    late_night_start_time        TIME         NOT NULL,
    late_night_end_time          TIME         NOT NULL DEFAULT '05:00:00',
    late_night_surcharge_percent INTEGER      NOT NULL
                                 CHECK (late_night_surcharge_percent BETWEEN 0 AND 100),
    tax_rate_percent             INTEGER      NOT NULL
                                 CHECK (tax_rate_percent BETWEEN 0 AND 100),
    table_charge_per_guest       INTEGER      NOT NULL CHECK (table_charge_per_guest >= 0),
    griddle_capacity             INTEGER      NOT NULL CHECK (griddle_capacity >= 1),
    order_number_start           INTEGER      NOT NULL CHECK (order_number_start >= 1),
    accepting_orders             BOOLEAN      NOT NULL,
    -- 動作確認のために営業時間を無視する。既定は false
    always_open                  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at                   TIMESTAMP    NOT NULL
);

-- 営業日ごとの注文番号の採番。行を掴んで進める
CREATE TABLE daily_counter (
    business_date DATE    PRIMARY KEY,
    last_number   INTEGER NOT NULL
);

CREATE TABLE staff_user (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(40)  NOT NULL UNIQUE,
    -- BCrypt のハッシュ。平文は保存しない
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(40)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
