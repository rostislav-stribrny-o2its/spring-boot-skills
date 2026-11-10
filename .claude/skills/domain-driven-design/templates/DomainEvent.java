// Illustrative: several types in one listing — split into one file per type in real code.
package com.example.common.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Base interface for all domain events.
 * Events are immutable facts about something that happened in the domain.
 * The eventId identifies the event message (deduplication by consumers); it is not a
 * database primary key. Aggregate references are the aggregates' Long IDs.
 */
public interface DomainEvent {
    UUID eventId();
    Instant occurredAt();
}

// --- Concrete domain events as records ---

package com.example.order.domain.event;

import com.example.common.domain.DomainEvent;
import com.example.order.domain.model.Money;

import java.time.Instant;
import java.util.UUID;

public record OrderPlaced(
    UUID eventId,
    Instant occurredAt,
    Long orderId,
    Long customerId,
    Money total
) implements DomainEvent {

    public static OrderPlaced of(Order order) {
        return new OrderPlaced(
            UUID.randomUUID(),
            Instant.now(),
            order.getId(),
            order.getCustomerId(),
            order.getTotal()
        );
    }
}

public record OrderCancelled(
    UUID eventId,
    Instant occurredAt,
    Long orderId,
    Long customerId,
    String reason
) implements DomainEvent {

    public OrderCancelled(Long orderId, Long customerId, String reason, Instant occurredAt) {
        this(UUID.randomUUID(), occurredAt, orderId, customerId, reason);
    }
}
