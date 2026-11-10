// ✅ GOOD — rich domain model, behavior methods, domain events, no public setters.
// Plain domain class: no Spring Data / JPA / Jackson annotations. It is persisted as
// OrderEntity (Spring Data JDBC) through OrderPersistenceMapper (MapStruct).

public class Order {

    private final Long id;                   // null until the first save (database identity)
    private final Long version;              // read version, copied to the entity for @Version
    private final Long customerId;           // other aggregate referenced by ID
    private final EmailAddress customerEmail;
    private final Currency currency;
    private OrderStatus status;
    private Money total;
    private final List<OrderItem> items = new ArrayList<>();
    private final List<Object> domainEvents = new ArrayList<>();

    private Order(Long id, Long version, Long customerId, EmailAddress customerEmail, Currency currency) {
        this.id = id;
        this.version = version;
        this.customerId = Objects.requireNonNull(customerId);
        this.customerEmail = Objects.requireNonNull(customerEmail);
        this.currency = Objects.requireNonNull(currency);
        this.status = OrderStatus.DRAFT;
        this.total = Money.zero(currency);
    }

    // Static factory — enforces valid initial state
    public static Order create(Long customerId, EmailAddress email, Currency currency) {
        return new Order(null, null, customerId, email, currency);   // ID assigned by the database
    }

    // Behavior methods enforce invariants
    public void addItem(Long productId, String name, int quantity, Money unitPrice) {
        if (status != OrderStatus.DRAFT)
            throw new OrderNotModifiableException(id);
        if (quantity <= 0)
            throw new InvalidQuantityException(quantity);
        if (!unitPrice.currency().equals(currency))
            throw new CurrencyMismatchException(currency, unitPrice.currency());
        items.add(new OrderItem(productId, name, quantity, unitPrice));
        recalculateTotal();
    }

    public void place() {
        if (items.isEmpty())
            throw new EmptyOrderException(id);
        if (status != OrderStatus.DRAFT)
            throw new IllegalOrderStateTransitionException(status, OrderStatus.PLACED);
        this.status = OrderStatus.PLACED;
        domainEvents.add(OrderPlaced.of(this));
    }

    public void cancel(String reason) {
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED)
            throw new IllegalOrderStateTransitionException(status, OrderStatus.CANCELLED);
        this.status = OrderStatus.CANCELLED;
        domainEvents.add(new OrderCancelled(id, customerId, reason, Instant.now()));
    }

    public List<Object> pullDomainEvents() {
        var events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void recalculateTotal() {
        this.total = items.stream()
            .map(OrderItem::lineTotal)
            .reduce(Money.zero(currency), Money::add);
    }

    // No public setters — state changes only through behavior methods
    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public Long getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public Money getTotal() { return total; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
}

// Optional typed ID inside the domain (needs Spring Data JDBC converters if used on the entity);
// never generated in Java — the value comes from the database identity.
public record OrderId(Long value) {
    public OrderId { Objects.requireNonNull(value); }
}
