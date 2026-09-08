package com.refundops;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RefundController {
    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication authentication, HttpServletRequest request) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("authenticated", authenticated);
        session.put("user", authenticated ? DemoUsers.get(authentication.getName()) : null);
        session.put("csrf", Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName(),
                "parameterName", csrf.getParameterName()));
        return session;
    }

    @GetMapping("/dashboard")
    public RefundModels.Dashboard dashboard() {
        return service.dashboard();
    }

    @PostMapping("/refunds")
    public ResponseEntity<RefundModels.RefundResult> create(
            Authentication authentication, @Valid @RequestBody RefundRequest request) {
        RefundModels.RefundResult result = service.create(authentication.getName(), request);
        return ResponseEntity.status(result.replayed ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @PostMapping("/refunds/{refundId}/approve")
    public RefundModels.RefundResult approve(
            Authentication authentication, @PathVariable String refundId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return service.approve(authentication.getName(), refundId, request);
    }

    @PostMapping("/refunds/{refundId}/reject")
    public RefundModels.RefundResult reject(
            Authentication authentication, @PathVariable String refundId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return service.reject(authentication.getName(), refundId, request);
    }

    @PostMapping("/demo/reset")
    public RefundModels.Dashboard reset() {
        return service.reset();
    }
}
