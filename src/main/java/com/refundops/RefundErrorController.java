package com.refundops;

import java.util.Map;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        Object errorStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = errorStatus instanceof Integer ? (Integer) errorStatus : 404;
        HttpStatus resolved = HttpStatus.resolve(status);
        String code = status >= 500 ? "INTERNAL_ERROR" : resolved == null ? "REQUEST_ERROR" : resolved.name();
        String message = status >= 500 ? "An unexpected server error occurred."
                : resolved == null ? "The request could not be completed." : resolved.getReasonPhrase();
        // Servlet error dispatch keeps unexpected failures logged without exposing internal exception details.
        return ResponseEntity.status(status).body(ApiErrors.body(code, message));
    }
}
