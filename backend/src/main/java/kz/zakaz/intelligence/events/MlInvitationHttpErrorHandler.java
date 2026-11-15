package kz.zakaz.intelligence.events;

import java.util.NoSuchElementException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=MlInvitationConsentController.class)
public class MlInvitationHttpErrorHandler {
    public record Problem(String code){}
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Problem> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new Problem("ACCESS_DENIED"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Problem> invalid(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new Problem("INVALID_INPUT"));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Problem> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new Problem("SHARING_CONFLICT"));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Problem> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new Problem("NOT_FOUND"));
    }
}
