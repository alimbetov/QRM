package kz.zakaz.deal;

import java.util.NoSuchElementException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=SellerInvitationBidWorkspaceController.class)
public class SellerInvitationBidWorkspaceErrors {
    public record Error(String code) {}
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Error> unavailable(NoSuchElementException ignored) {
        // Do not reveal existence of private/expired/unconsented demands.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new Error("INVITATION_NOT_AVAILABLE"));
    }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Error> forbidden(SecurityException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Error("SELLER_FORBIDDEN"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Error> invalid(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new Error("INVALID_BID"));
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Error> conflict(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new Error("BID_CONFLICT"));
    }
}
