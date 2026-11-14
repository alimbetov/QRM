package kz.zakaz.order;

import java.util.NoSuchElementException;
import kz.zakaz.security.SellerAccountController;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={SellerOrdersController.class,SellerAccountController.class})
public class SellerOrdersHttpErrorHandler {
    public record Problem(String code) {}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> invalid(IllegalArgumentException e){
        return ResponseEntity.unprocessableEntity().body(new Problem("INVALID_INPUT"));
    }
    @ExceptionHandler({AccessDeniedException.class,SecurityException.class})
    ResponseEntity<Problem> forbidden(RuntimeException e){
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Problem("ACCESS_DENIED"));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Problem> notFound(NoSuchElementException e){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Problem("NOT_FOUND"));
    }
}
