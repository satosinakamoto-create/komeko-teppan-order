package jp.komeko.order.web;

import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 例外をまとめて受け止めて、お客さん・スタッフに見せる画面へ変換する。
 *
 * <p>これが無いと、想定外のエラーのときに Spring 既定の白い画面
 * （通称ホワイトラベルエラーページ）が出てしまい、
 * 何が起きたのか分からないうえに内部情報が漏れることもあります。
 *
 * <p>ポイントは「お客さんに見せてよい情報だけを画面に出し、
 * 詳しい内容はログに残す」ことです。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 商品や注文が見つからない → 404 の専用ページ。 */
    @ExceptionHandler({MenuService.MenuItemNotFoundException.class, OrderService.OrderNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(RuntimeException e, Model model) {
        log.info("見つかりませんでした: {}", e.getMessage());
        model.addAttribute("title", "見つかりませんでした");
        model.addAttribute("message", e.getMessage());
        model.addAttribute("hint", "URL が古いか、削除された可能性があります。もう一度 QR コードを読み取ってください。");
        return "error/message";
    }

    /** 業務ルール上受け付けられない → 400。 */
    @ExceptionHandler(OrderRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleRejected(OrderRejectedException e, Model model) {
        model.addAttribute("title", "ご注文を承れませんでした");
        model.addAttribute("message", String.join("\n", e.getReasons()));
        model.addAttribute("hint", "お手数ですが、内容をご確認のうえもう一度お試しください。");
        return "error/message";
    }

    /** 画像アップロードのサイズ超過。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public String handleUploadTooLarge(Model model) {
        model.addAttribute("title", "画像が大きすぎます");
        model.addAttribute("message", "アップロードできる画像は 5MB までです。");
        model.addAttribute("hint", "画像を縮小してからもう一度お試しください。");
        return "error/message";
    }

    /**
     * 状態遷移の違反など、その他の想定内エラー。
     * 詳細はログにだけ残し、画面には一般的な文言を出します。
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleIllegalState(IllegalStateException e, Model model) {
        log.warn("不正な操作: {}", e.getMessage());
        model.addAttribute("title", "その操作は行えません");
        model.addAttribute("message", e.getMessage());
        model.addAttribute("hint", "画面を再読み込みして、最新の状態をご確認ください。");
        return "error/message";
    }
}
