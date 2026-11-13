package kz.zakaz.deal;

import java.util.NoSuchElementException;
import kz.zakaz.inventory.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Safe, deterministic error mappings for the authenticated commerce API only. */
@RestControllerAdvice(assignableTypes=AuthenticatedCommerceController.class)
public final class CommerceHttpErrorHandler {
    public record Problem(String code){}
    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Problem> unprocessable(IllegalArgumentException e){
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new Problem("INVALID_BUSINESS_INPUT"));
    }
    @ExceptionHandler({IllegalStateException.class,IdempotencyConflictException.class,
        InsufficientStockException.class,InventoryOperationConflictException.class,
        DataIntegrityViolationException.class})
    public ResponseEntity<Problem> conflict(RuntimeException e){
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new Problem("BUSINESS_CONFLICT"));
    }
    @ExceptionHandler({AccessDeniedException.class,SecurityException.class})
    public ResponseEntity<Problem> forbidden(RuntimeException e){
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new Problem("ACCESS_DENIED"));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Problem> notFound(NoSuchElementException e){
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new Problem("NOT_FOUND"));
    }
}
