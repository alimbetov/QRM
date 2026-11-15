package kz.zakaz.security;

import java.util.NoSuchElementException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=SellerCatalogWorkspaceController.class)
public class SellerCatalogWorkspaceErrors {
    public record Problem(String code) {}
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Problem> notFound(NoSuchElementException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Problem("NOT_FOUND"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Problem> invalid(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new Problem("INVALID_DRAFT"));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Problem> conflict(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new Problem("DRAFT_CONFLICT"));
    }
}
