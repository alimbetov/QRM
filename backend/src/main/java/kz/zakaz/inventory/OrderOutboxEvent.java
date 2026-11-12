package kz.zakaz.inventory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import kz.zakaz.order.CustomerOrder;

/**
 * Atomic outbox record stored with order confirmation; no worker is attached yet.
 * Delivery is at-least-once in future, consumers must deduplicate by event ID.
 */
@Entity @Table(name="order_outbox_event",uniqueConstraints=
    @UniqueConstraint(name="uk_outbox_order_event",columnNames={"aggregate_id","event_type"}))
public class OrderOutboxEvent {
    @Id @Column(nullable=false) private UUID id;
    @Version private Long version;
    @Column(name="aggregate_id",nullable=false,updatable=false) private UUID aggregateId;
    @Column(name="event_type",nullable=false,updatable=false,length=64) private String eventType;
    @Column(nullable=false,updatable=false,length=512) private String payload;
    @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
    @Column(name="published_at") private Instant publishedAt;
    protected OrderOutboxEvent(){}
    public static OrderOutboxEvent orderConfirmed(CustomerOrder order,Instant now) {
        Objects.requireNonNull(order);Objects.requireNonNull(now);
        var e=new OrderOutboxEvent();e.id=UUID.randomUUID();e.aggregateId=order.getId();
        e.eventType="ORDER_CONFIRMED";
        e.payload="{\"eventId\":\""+e.id+"\",\"orderId\":\""+order.getId()
                 +"\",\"sellerId\":\""+order.getSellerId()+"\"}";
        e.occurredAt=now;return e;
    }
}
