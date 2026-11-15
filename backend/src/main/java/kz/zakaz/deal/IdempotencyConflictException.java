package kz.zakaz.deal;

/** Reused request key with different content, or already accepted demand. Maps to HTTP 409 later. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message){super(message);}
}
