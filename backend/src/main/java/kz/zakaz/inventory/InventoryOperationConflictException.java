package kz.zakaz.inventory;

/** Same seller operation key with a different stock quantity, SKU or location. */
public final class InventoryOperationConflictException extends RuntimeException {
    public InventoryOperationConflictException(String message){super(message);}
}
