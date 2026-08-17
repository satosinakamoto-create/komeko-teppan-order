package jp.komeko.order.web.admin;

import jp.komeko.order.service.BackupService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * バックアップの管理画面。
 *
 * <p>できることは「状況を見る」と「今すぐ取る」の 2 つだけです。
 * <b>復元ボタンは意図的にありません。</b>
 * リストアは今のデータを過去で上書きする操作なので、
 * ボタン一つで実行できると誤操作の被害が最大級になります。
 * 復元手順は docs/バックアップと復元.md を参照してください。
 */
@Controller
@RequestMapping("/admin/backups")
public class AdminBackupController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final BackupService backupService;

    public AdminBackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("activeNav", "admin");
        model.addAttribute("backups", backupService.listBackups());
        model.addAttribute("backupDir", backupService.getBackupDirPath());
        model.addAttribute("supported", backupService.isBackupSupported());
        model.addAttribute("lastMessage", backupService.getLastMessage());
        model.addAttribute("lastFailed", backupService.isLastFailed());
        model.addAttribute("lastSuccessLabel",
                backupService.getLastSuccessAt() != null
                        ? backupService.getLastSuccessAt().format(TIME_FORMAT)
                        : "（この起動中はまだ）");
        return "admin/backups";
    }

    /** 「今すぐバックアップ」ボタン。 */
    @PostMapping("/run")
    public String run(RedirectAttributes redirectAttributes) {
        try {
            String message = backupService.backupNow("手動");
            redirectAttributes.addFlashAttribute("flashSuccess", message);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return "redirect:/admin/backups";
    }
}
