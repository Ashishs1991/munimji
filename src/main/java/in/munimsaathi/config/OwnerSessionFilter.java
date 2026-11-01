package in.munimsaathi.config;

import in.munimsaathi.tenant.MembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class OwnerSessionFilter extends OncePerRequestFilter {

    public static final String OWNER_ID = "ownerId";

    private final MunimProperties properties;
    private final MembershipRepository memberships;

    public OwnerSessionFilter(MunimProperties properties, MembershipRepository memberships) {
        this.properties = properties;
        this.memberships = memberships;
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
        String token = token(request);
        UUID ownerId;
        try {
            ownerId = OwnerTokens.verify(secret, token);
        } catch (RuntimeException exception) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign in is required.");
            return;
        }
        if (memberships.findByAuthUserId(ownerId).isEmpty()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This sign-in is not an owner.");
            return;
        }
        String requested = request.getParameter("userId");
        if (requested != null && !requested.isBlank() && !ownerId.toString().equals(requested)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "That user is not the signed-in owner.");
            return;
        }
        request.setAttribute(OWNER_ID, ownerId);
        Cookie cookie = new Cookie("munim_session", token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60);
        response.addCookie(cookie);
        filterChain.doFilter(request, response);
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
