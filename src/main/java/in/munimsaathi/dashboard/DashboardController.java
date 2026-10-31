package in.munimsaathi.dashboard;

import in.munimsaathi.tenant.BusinessAccessDeniedException;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Controller
public class DashboardController {

    private final OwnerDashboard dashboard;
    private final TenantContext tenants;

    public DashboardController(OwnerDashboard dashboard, TenantContext tenants) {
        this.dashboard = dashboard;
        this.tenants = tenants;
    }

    @GetMapping("/dashboard")
    public String show(
            @RequestParam UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model
    ) {
        model.addAttribute("userId", userId);
        model.addAttribute("overview", dashboard.overview(userId, date));
        return "dashboard";
    }

    @PostMapping("/dashboard/business")
    public String selectBusiness(
            @RequestParam UUID userId,
            @RequestParam UUID businessId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        tenants.selectBusiness(userId, businessId);
        return "redirect:/dashboard?userId=" + userId + "&date=" + date;
    }

    @PostMapping("/dashboard/threshold")
    public String setThreshold(
            @RequestParam UUID userId,
            @RequestParam UUID productId,
            @RequestParam BigDecimal minimum,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        dashboard.setThreshold(userId, productId, minimum);
        return "redirect:/dashboard?userId=" + userId + "&date=" + date;
    }

    @ExceptionHandler(BusinessAccessDeniedException.class)
    public ResponseEntity<String> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This owner cannot open that business.");
    }
}
