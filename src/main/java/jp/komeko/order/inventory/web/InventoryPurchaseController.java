package jp.komeko.order.inventory.web;

import jakarta.validation.Valid;
import jp.komeko.order.inventory.config.InventoryProperties;
import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.service.*;
import jp.komeko.order.inventory.web.form.PurchaseForm;
import jp.komeko.order.inventory.web.form.PurchaseLineForm;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.ImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仕入れ・経費の画面（{@code /inventory/purchases}）。
 *
 * <p><b>画面の流れ</b>
 * <pre>
 *   一覧 ──「レシートを登録」──▶ 撮影 or 手入力
 *                                   │
 *                                   ├─ 写真を選ぶ ──▶ AI が読む ──▶ 確認画面（直せる）
 *                                   └─ 手入力 ─────────────────────▶ 確認画面（空欄）
 *                                                                        │
 *                                                                      保存 ──▶ 一覧へ
 * </pre>
 *
 * <p><b>{@code @ConditionalOnProperty} が付いている意味</b><br>
 * {@code app.inventory.enabled=false} だと、このクラス自体が作られません。
 * つまり URL ごと存在しなくなり、404 になります。
 * 本番で不具合が出たときに、コードを直して再ビルドするのではなく
 * <b>設定 1 行と再起動</b>で従来の姿に戻せるようにするための非常口です。
 *
 * <p><b>表示は GET、変更は POST</b><br>
 * 既存の管理画面と同じ規律です。公開デモでゲストに「見るだけ」を許す仕組みが
 * この分け方に乗っているので、GET で状態が変わる口を作ってはいけません。
 */
@Controller
@RequestMapping("/inventory/purchases")
@ConditionalOnProperty(prefix = "app.inventory", name = "enabled", havingValue = "true")
public class InventoryPurchaseController {

    private static final Logger log = LoggerFactory.getLogger(InventoryPurchaseController.class);

    private static final int PAGE_SIZE = 30;

    private final PurchaseService purchaseService;
    private final TaxRuleService taxRuleService;
    private final IngredientService ingredientService;
    private final ReceiptReader receiptReader;
    private final ImageStorageService imageStorage;
    private final InventoryProperties properties;

    public InventoryPurchaseController(PurchaseService purchaseService,
                                       TaxRuleService taxRuleService,
                                       IngredientService ingredientService,
                                       ReceiptReader receiptReader,
                                       ImageStorageService imageStorage,
                                       InventoryProperties properties) {
        this.purchaseService = purchaseService;
        this.taxRuleService = taxRuleService;
        this.ingredientService = ingredientService;
        this.receiptReader = receiptReader;
        this.imageStorage = imageStorage;
        this.properties = properties;
    }

    /** どの画面でも使う選択肢を、まとめてモデルに載せる。 */
    @ModelAttribute
    public void commonAttributes(Model model) {
        model.addAttribute("categories", PurchaseCategory.values());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("evidenceTypes", EvidenceType.values());
        model.addAttribute("ocrAvailable", receiptReader.isAvailable());
        model.addAttribute("ingredients", ingredientService.activeIngredients());
    }

    // ========================================================================
    //  一覧・検索・月次のまとめ
    // ========================================================================

    /**
     * 仕入れ・経費の一覧。
     *
     * <p>検索の 3 項目（取引日・金額・取引先）は画面の便利機能ではなく、
     * <b>電子帳簿保存法でシステムに求められている機能</b>です。
     * 日付と金額は範囲で指定でき、組み合わせて絞り込めます。
     */
    @GetMapping
    public String index(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) Integer minAmount,
                        @RequestParam(required = false) Integer maxAmount,
                        @RequestParam(required = false) String store,
                        @RequestParam(defaultValue = "false") boolean includeDeleted,
                        @RequestParam(required = false) String month,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {

        YearMonth targetMonth = parseMonth(month, purchaseService.today());
        PurchaseSummary summary = purchaseService.summarize(targetMonth);

        Page<Purchase> results = purchaseService.search(
                from, to, minAmount, maxAmount, store, includeDeleted,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));

        model.addAttribute("summary", summary);
        model.addAttribute("month", targetMonth);
        model.addAttribute("prevMonth", targetMonth.minusMonths(1));
        model.addAttribute("nextMonth", targetMonth.plusMonths(1));
        model.addAttribute("results", results);
        model.addAttribute("attention", purchaseService.needingAttention(
                targetMonth.atDay(1), targetMonth.atEndOfMonth()));
        model.addAttribute("masterWarnings", taxRuleService.masterWarnings(purchaseService.today()));

        // 検索条件を画面に返して、入力欄に残す
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("minAmount", minAmount);
        model.addAttribute("maxAmount", maxAmount);
        model.addAttribute("store", store);
        model.addAttribute("includeDeleted", includeDeleted);

        return "inventory/purchases";
    }

    /**
     * 検索結果を CSV でダウンロードする（設計 6 章の約束。税務調査・税理士への受け渡し用）。
     *
     * <p>電子帳簿保存法の検索要件そのものは画面の検索で満たしていますが、
     * 調査官や税理士に「その結果をください」と言われたとき、
     * 画面を見せる以外の手が無いのでは話になりません。
     * <b>検索と同じ条件</b>で全件（ページ分割なし）を出します。
     *
     * <p>ダウンロードは読み取りなので GET でよい（PRG の対象は状態を変える操作）。
     * 文字コードは UTF-8 + BOM。Excel で開く人がほとんどで、
     * BOM が無いと日本語 Excel が文字化けするためです。
     */
    @GetMapping("/export.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer minAmount,
            @RequestParam(required = false) Integer maxAmount,
            @RequestParam(required = false) String store,
            @RequestParam(defaultValue = "true") boolean includeDeleted) {

        // 組み立てはサービス側（明細の遅延読み込みがあるため。規約どおり）
        String csv = purchaseService.exportCsv(from, to, minAmount, maxAmount, store, includeDeleted);
        String filename = "shiire-" + purchaseService.today() + ".csv";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 1 件の詳細。 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Purchase purchase = purchaseService.findWithLines(id);
        if (purchase == null) {
            return "redirect:/inventory/purchases";
        }
        model.addAttribute("purchase", purchase);
        return "inventory/purchase-detail";
    }

    // ========================================================================
    //  登録（撮影 → 読取 → 確認 → 保存）
    // ========================================================================

    /** 登録の入口。写真を選ぶか、手入力を選ぶ。 */
    @GetMapping("/new")
    public String newPurchase(Model model) {
        model.addAttribute("purchaseForm", PurchaseForm.manual(purchaseService.today()));
        model.addAttribute("stage", "start");
        return "inventory/purchase-form";
    }

    /**
     * 写真を読み取って、確認画面を出す。<b>まだ保存しません。</b>
     *
     * <p>画像はこの時点で保存します。確認に手間取っても写真が消えないためです。
     * 読み取りに失敗しても画面は出します（空欄の確認画面＝手入力と同じ状態）。
     */
    @PostMapping("/read")
    public String read(@RequestParam("image") MultipartFile image,
                       Model model,
                       RedirectAttributes redirect) {
        if (image == null || image.isEmpty()) {
            redirect.addFlashAttribute("flashErrors", List.of("レシートの画像を選んでください"));
            return "redirect:/inventory/purchases/new";
        }

        List<String> notices = new ArrayList<>();
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (Exception e) {
            redirect.addFlashAttribute("flashErrors", List.of("画像を読み込めませんでした"));
            return "redirect:/inventory/purchases/new";
        }

        // ── 画質の確認（電子帳簿保存法は 200dpi 相当以上を求める） ──
        String qualityWarning = checkImageQuality(bytes);
        if (qualityWarning != null) {
            notices.add(qualityWarning);
        }

        String imagePath;
        try {
            imagePath = imageStorage.store(image);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("flashErrors", List.of(e.getMessage()));
            return "redirect:/inventory/purchases/new";
        }

        ReceiptReading reading = receiptReader.read(bytes, image.getContentType());
        LocalDate today = purchaseService.today();
        int standardRate = taxRuleService.taxRateOn(TaxRatePeriod.CLASS_STANDARD, today);

        PurchaseForm form = PurchaseForm.fromReading(reading, imagePath, today, standardRate);
        if (reading.isEmpty()) {
            notices.add(receiptReader.isAvailable()
                    ? "レシートを読み取れませんでした。お手数ですが手で入力してください。"
                    : "AI 読み取りは設定されていません（API キー未設定）。手で入力してください。");
        }
        suggestEvidence(form);
        int learned = applyRememberedAliases(form);
        if (learned > 0) {
            notices.add(learned + " 行は前に教えていただいた内容から、食材と量を自動で入れました。"
                    + "違っていたら直してください。");
        }

        model.addAttribute("purchaseForm", form);
        model.addAttribute("stage", "confirm");
        model.addAttribute("notices", notices);
        return "inventory/purchase-form";
    }

    /** 手入力で確認画面を開く（写真なし）。 */
    @PostMapping("/manual")
    public String manual(Model model) {
        PurchaseForm form = PurchaseForm.manual(purchaseService.today());
        // ★ 手入力でも証憑区分の候補を入れる。
        //   入れないと、セレクトの先頭（適格簡易請求書＝全額控除）が既定のまま送信され、
        //   八百屋の手書き領収書が控除率 100% で保存されてしまう。
        //   登録番号なし・合計不明の状態での候補は「インボイスなし」（経過措置）になる。
        //   画面の説明文（候補を入れてあります）とも、これでつじつまが合う。
        suggestEvidence(form);
        model.addAttribute("purchaseForm", form);
        model.addAttribute("stage", "confirm");
        return "inventory/purchase-form";
    }

    /** 確認画面の内容を保存する。 */
    @PostMapping
    public String create(@Valid @ModelAttribute("purchaseForm") PurchaseForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal StaffUserDetails user,
                         Model model,
                         RedirectAttributes redirect) {

        List<PurchaseLineForm> filled = form.filledLines();
        if (filled.isEmpty()) {
            bindingResult.reject("lines.empty", "明細を1行以上入力してください");
        }
        for (PurchaseLineForm line : filled) {
            if (line.getAmount() == null) {
                bindingResult.reject("lines.amount", "「" + line.getItemText() + "」の金額を入力してください");
            }
            if (line.getTaxRatePercent() == null) {
                bindingResult.reject("lines.taxRate", "「" + line.getItemText() + "」の税率を選んでください");
            }
        }

        if (bindingResult.hasErrors()) {
            // リダイレクトせずに描き直す。入力内容とエラーをそのまま出すため。
            model.addAttribute("stage", "confirm");
            return "inventory/purchase-form";
        }

        List<PurchaseDraft.LineDraft> lines = new ArrayList<>();
        for (PurchaseLineForm line : filled) {
            lines.add(new PurchaseDraft.LineDraft(
                    line.getItemText(), line.getQuantity(), line.getAmount(),
                    line.getTaxRatePercent(), line.getTaxAmount(), line.getCategory(),
                    line.getIngredientId(), line.getStockQty(), line.isLearnAlias()));
        }

        PurchaseDraft draft = new PurchaseDraft(
                form.getPurchasedOn(), form.getReceivedOn(), form.getStoreName(),
                form.getTotalAmount(), form.getPaymentMethod(), form.getRegistrationNumber(),
                form.getEvidenceType(), form.getImagePath(), form.getOcrJson(),
                form.getMemo(), form.isEquivalenceChecked(), lines);

        Purchase saved = purchaseService.record(draft, user != null ? user.getId() : null);

        List<String> messages = new ArrayList<>();
        messages.add("「" + saved.getStoreName() + "」の仕入れを登録しました");
        if (saved.canDiscardPaper()) {
            messages.add("紙のレシートは破棄して構いません（同等確認済み）");
        } else if (saved.isPaperRetentionRequired()) {
            messages.add("入力期限を過ぎているため、このレシートの紙原本は保管してください");
        } else {
            messages.add("紙と見比べる確認がまだです。詳細画面から確認すると原本を破棄できます");
        }
        int stocked = 0;
        int pending = 0;
        for (PurchaseLine line : saved.getLines()) {
            if (line.feedsStock()) {
                stocked++;
            } else if (line.needsQuantityLearning()) {
                pending++;
            }
        }
        if (stocked > 0) {
            messages.add(stocked + " 行を在庫に足しました");
        }
        if (pending > 0) {
            messages.add(pending + " 行は量が分からないので在庫に入れていません（食材マスタから教えられます）");
        }

        redirect.addFlashAttribute("flashSuccess", String.join(" / ", messages));
        return "redirect:/inventory/purchases";
    }

    // ========================================================================
    //  記録の手入れ
    // ========================================================================

    /**
     * 「紙と見比べました」を記録する。
     *
     * <p><b>「破棄して構いません」は無条件に言ってはいけません。</b>
     * 入力期限を過ぎたレシートは、確認しても紙の原本の保管が必要です
     * （{@link Purchase#canDiscardPaper}）。以前ここが無条件だったため、
     * 同じ画面の上部で「保管してください」と警告しながら、
     * ボタンを押すと「破棄して構いません」と言う自己矛盾が起きていました。
     * 店主がこれを信じて原本を捨てると電子帳簿保存法違反になります。
     */
    @PostMapping("/{id}/checked")
    public String markChecked(@PathVariable Long id, RedirectAttributes redirect) {
        purchaseService.markEquivalenceChecked(id);
        Purchase purchase = purchaseService.findWithLines(id);
        if (purchase != null && purchase.canDiscardPaper()) {
            redirect.addFlashAttribute("flashSuccess",
                    "同等確認を記録しました。紙の原本は破棄して構いません");
        } else {
            redirect.addFlashAttribute("flashSuccess",
                    "同等確認を記録しました。ただし入力期限を過ぎているため、紙の原本は引き続き保管してください");
        }
        return "redirect:/inventory/purchases/" + id;
    }

    /**
     * 取り消す（論理削除）。
     *
     * <p>行は消しません。電子帳簿保存法では
     * <b>削除したという事実まで含めて残し、削除済みも検索できる</b>ことが求められます。
     */
    @PostMapping("/{id}/delete")
    public String softDelete(@PathVariable Long id,
                             @RequestParam(required = false) String reason,
                             RedirectAttributes redirect) {
        purchaseService.softDelete(id, reason);
        redirect.addFlashAttribute("flashInfo", "取り消しました（記録は履歴として残ります）");
        return "redirect:/inventory/purchases";
    }

    // ========================================================================
    //  補助
    // ========================================================================

    /**
     * 覚えている品名の行に、食材と量をあらかじめ入れておく。
     *
     * <p><b>ここが「1 回教えたら次から自動」の見えている部分です。</b>
     * 2 回目からは確認画面を開いた瞬間にもう埋まっていて、
     * 人は違うところだけ直せば済みます。
     *
     * <p>入れるのはあくまで<b>たたき台</b>で、保存前に必ず人が見ます。
     * 商品が入れ替わって内容量が変わることもあるので、
     * 自動で入った値も直せる状態にしておきます。
     *
     * @return 自動で埋めた行数
     */
    private int applyRememberedAliases(PurchaseForm form) {
        List<String> itemTexts = new ArrayList<>();
        for (PurchaseLineForm line : form.getLines()) {
            if (line.getItemText() != null && !line.getItemText().isBlank()) {
                itemTexts.add(line.getItemText());
            }
        }
        if (itemTexts.isEmpty()) {
            return 0;
        }

        Map<String, ItemAlias> remembered = ingredientService.recallAll(itemTexts);
        int filled = 0;
        for (PurchaseLineForm line : form.getLines()) {
            String key = AliasText.normalize(line.getItemText());
            ItemAlias alias = key != null ? remembered.get(key) : null;
            if (alias == null) {
                continue;
            }
            line.setIngredientId(alias.getIngredient().getId());
            line.setStockQty(alias.toStockQty(line.getQuantity()));
            filled++;
        }
        return filled;
    }

    /** 証憑区分のたたき台を入れる（人が確認画面で直せる）。 */
    private void suggestEvidence(PurchaseForm form) {
        String normalized = RegistrationNumber.normalize(form.getRegistrationNumber());
        // 合計が読めていないときは null のまま渡す。
        // 0 円に変換すると「1 万円未満 → 少額特例（全額控除）」の候補になってしまう。
        form.setEvidenceType(purchaseService.suggestEvidenceType(
                normalized, form.getTotalAmount(), form.getPurchasedOn()));
    }

    /**
     * 画質が足りているかを見る。
     *
     * <p>電子帳簿保存法のスキャナ保存は「200dpi 相当以上・カラー」を求めます。
     * dpi はファイルに書かれた値ではなく<b>紙の大きさと画素数から決まる</b>ので、
     * ここでは総画素数で大まかに見ています。
     * 足りなくても保存は止めません（撮り直しを促すだけ）。
     * 止めてしまうと、記録そのものが残らないほうの損が大きいからです。
     */
    private String checkImageQuality(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return null;   // 判定できない形式。ここでは何も言わない
            }
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels < properties.minImagePixels()) {
                return "画像が粗いようです（" + image.getWidth() + "×" + image.getHeight()
                        + "）。紙のレシートを捨てる場合は、もう少し寄って撮り直すことをおすすめします。";
            }
        } catch (Exception e) {
            log.debug("画像の画質判定に失敗しました: {}", e.toString());
        }
        return null;
    }

    private YearMonth parseMonth(String value, LocalDate today) {
        if (value == null || value.isBlank()) {
            return YearMonth.from(today);
        }
        try {
            return YearMonth.parse(value);
        } catch (Exception e) {
            return YearMonth.from(today);
        }
    }
}
