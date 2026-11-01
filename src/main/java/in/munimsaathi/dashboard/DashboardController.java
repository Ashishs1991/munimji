package in.munimsaathi.dashboard;

import in.munimsaathi.config.OwnerSessionFilter;
import in.munimsaathi.tenant.BusinessAccessDeniedException;
import in.munimsaathi.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

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
            HttpServletRequest request,
            @RequestParam(required = false) UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model
    ) {
        UUID ownerId = owner(request, userId);
        model.addAttribute("userId", ownerId);
        model.addAttribute("csrfToken", request.getAttribute(OwnerSessionFilter.CSRF_TOKEN));
        model.addAttribute("overview", dashboard.overview(ownerId, date));
        return "dashboard";
    }

    @PostMapping("/dashboard/business")
    public String selectBusiness(
            HttpServletRequest request,
            @RequestParam(required = false) UUID userId,
            @RequestParam UUID businessId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        UUID ownerId = owner(request, userId);
        tenants.selectBusiness(ownerId, businessId);
        return "redirect:/dashboard?userId=" + ownerId + "&date=" + date;
    }

    @PostMapping("/dashboard/threshold")
    public String setThreshold(
            HttpServletRequest request,
            @RequestParam(required = false) UUID userId,
            @RequestParam UUID productId,
            @RequestParam BigDecimal minimum,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        UUID ownerId = owner(request, userId);
        dashboard.setThreshold(ownerId, productId, minimum);
        return "redirect:/dashboard?userId=" + ownerId + "&date=" + date;
    }

    private static UUID owner(HttpServletRequest request, UUID requested) {
        Object value = request.getAttribute(OwnerSessionFilter.OWNER_ID);
        if (!(value instanceof UUID ownerId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in is required.");
        }
        if (requested != null && !requested.equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That user is not the signed-in owner.");
        }
        return ownerId;
    }

    @ExceptionHandler(BusinessAccessDeniedException.class)
    public ResponseEntity<String> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This owner cannot open that business.");
    }
}
