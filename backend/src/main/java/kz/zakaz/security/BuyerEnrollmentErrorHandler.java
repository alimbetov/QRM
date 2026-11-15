package kz.zakaz.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=BuyerEnrollmentController.class)
public class BuyerEnrollmentErrorHandler {
    public record Problem(String code) {}
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Problem> forbidden(AccessDeniedException ignored){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Problem("IDENTITY_NOT_ALLOWED"));
    }
}
