package kz.zakaz.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.StockTrackingMode;
import kz.zakaz.location.*;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal seller-declared receipt, NOT independently verified warehouse intake.
 * Existing balance must have been provisioned by a trusted catalog/inventory adapter.
 */
@Service
public class InventoryStockService {
    private final SellerIdentityService identity;
    private final InventoryLocationRepository locations;
    private final SellerSkuRepository skus;
    private final CatalogSkuFactsResolver catalog;
    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;

    public InventoryStockService(SellerIdentityService identity,
       InventoryLocationRepository locations,SellerSkuRepository skus,
       CatalogSkuFactsResolver catalog,InventoryBalanceRepository balances,
       InventoryMovementRepository movements){
        this.identity=identity;this.locations=locations;this.skus=skus;
        this.catalog=catalog;this.balances=balances;this.movements=movements;
    }
    @Transactional
    public UUID receive(JwtAuthenticationToken actor,UUID sellerId,UUID locationId,
                        UUID skuId,BigDecimal quantity,String idempotencyKey,Instant now){
        Objects.requireNonNull(sellerId);Objects.requireNonNull(locationId);
        Objects.requireNonNull(skuId);Objects.requireNonNull(now);
        if(idempotencyKey==null||!idempotencyKey.matches("[A-Za-z0-9_.:-]{1,80}"))
            throw new IllegalArgumentException("Receipt idempotency key required");
        if(quantity==null||quantity.signum()<=0||quantity.scale()>3||quantity.precision()>19)
            throw new IllegalArgumentException("Invalid receipt quantity");
        identity.requireSellerAdmin(actor,sellerId);
        var location=locations.findById(locationId)
            .orElseThrow(()->new IllegalArgumentException("Location not found"));
        if(!location.isActive()||!location.getSellerId().equals(sellerId))
            throw new SecurityException("Foreign/inactive inventory location");
        var sku=skus.findById(skuId).orElseThrow();
        var facts=catalog.resolveForSeller(sellerId,skuId);
        if(!facts.sellerId().equals(sellerId) ||
            sku.getTrackingMode()!=StockTrackingMode.TRACKED || sku.getSalesUnit()==null)
            throw new IllegalStateException("Stock unit/owner is not qualified");
        var balance=balances.findForUpdate(sellerId,locationId,skuId)
            .orElseThrow(()->new IllegalStateException("Inventory balance not provisioned"));
        var previous=movements.findBySellerIdAndKindAndSourceKey(
            sellerId,InventoryMovement.Kind.RECEIPT,idempotencyKey);
        if(previous.isPresent()) {
            var event=previous.get();
            if(!event.getSkuId().equals(skuId) ||
               !event.getLocationId().equals(locationId) ||
               event.getDeltaOnHand().compareTo(quantity)!=0)
                throw new InventoryOperationConflictException("Receipt key reused with different data");
            return event.getId();
        }
        balance.receive(quantity);
        var jwt=actor.getToken();
        var movement=InventoryMovement.receipt(sellerId,locationId,skuId,idempotencyKey,
            quantity,jwt.getIssuer().toString(),jwt.getSubject(),now);
        movements.saveAndFlush(movement);
        return movement.getId();
    }
}
