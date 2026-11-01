package in.munimsaathi.config;

import in.munimsaathi.tenant.BusinessContextRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@Component
public class OwnerSessionFilter extends OncePerRequestFilter {

    public static final String OWNER_ID = "ownerId";
    public static final String CSRF_TOKEN = "csrfToken";

    private final MunimProperties properties;
    private final MembershipRepository memberships;
    private final BusinessContextRepository contexts;

    public OwnerSessionFilter(
            MunimProperties properties,
            @Lazy MembershipRepository memberships,
            @Lazy BusinessContextRepository contexts
    ) {
        this.properties = properties;
        this.memberships = memberships;
        this.contexts = contexts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/webhooks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/dashboard") && !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String secret = properties.supabase() == null ? null : properties.supabase().jwtSecret();
        if (secret == null || secret.isBlank()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign-in is not configured.");
            return;
        }
        boolean bearer = bearer(request);
        String token = token(request);
        UUID ownerId;
        try {
            ownerId = OwnerTokens.verify(secret, token);
        } catch (RuntimeException exception) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign in is required.");
            return;
        }
        List<Membership> owned = memberships.findByAuthUserId(ownerId);
        if (owned.stream().noneMatch(membership -> membership.getRole() == MembershipRole.OWNER)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This sign-in is not an owner.");
            return;
        }
        if (!mayChange(request, owned, ownerId)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This sign-in is not an owner.");
            return;
        }
        String requested = request.getParameter("userId");
        if (requested != null && !requested.isBlank() && !ownerId.toString().equals(requested)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "That user is not the signed-in owner.");
            return;
        }
        String csrf = csrf(request);
        if (!bearer && mutation(request)) {
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.equals(originOf(request))) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "This request was not sent from the dashboard.");
                return;
            }
            String supplied = request.getHeader("X-CSRF-Token");
            if (supplied == null || supplied.isBlank()) {
                supplied = request.getParameter("csrf");
            }
            if (supplied == null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "A CSRF token is required.");
                return;
            }
        }
        request.setAttribute(OWNER_ID, ownerId);
        request.setAttribute(CSRF_TOKEN, csrf);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(request, token).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie(request, csrf).toString());
        filterChain.doFilter(request, response);
    }

    private boolean mayChange(HttpServletRequest request, List<Membership> owned, UUID ownerId) {
        if (!mutation(request)) {
            return true;
        }
        String requestedBusiness = request.getParameter("businessId");
        UUID businessId = null;
        if (requestedBusiness != null && !requestedBusiness.isBlank()) {
            try {
                businessId = UUID.fromString(requestedBusiness);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        } else {
            businessId = contexts.findById(ownerId).map(context -> context.getBusinessId()).orElse(null);
        }
        if (businessId == null) {
            return true;
        }
        UUID required = businessId;
        return owned.stream().anyMatch(membership ->
                membership.getBusinessId().equals(required) && membership.getRole() == MembershipRole.OWNER
        );
    }

    private static boolean bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ");
    }

    private static boolean mutation(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static String csrf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("munim_csrf".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String originOf(HttpServletRequest request) {
        StringBuilder origin = new StringBuilder();
        origin.append(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean standard = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        if (!standard) {
            origin.append(':').append(port);
        }
        return origin.toString();
    }

    private static ResponseCookie sessionCookie(HttpServletRequest request, String token) {
        return ResponseCookie.from("munim_session", token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(60 * 60)
                .build();
    }

    private static ResponseCookie csrfCookie(HttpServletRequest request, String csrf) {
        return ResponseCookie.from("munim_csrf", csrf)
                .httpOnly(false)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(60 * 60)
                .build();
    }

    private static String token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if ("munim_session".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }
}
