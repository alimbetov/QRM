package kz.zakaz.inventory;

/** Transaction must roll back; never silently create stock or partially reserve. */
public final class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message){super(message);}
}
