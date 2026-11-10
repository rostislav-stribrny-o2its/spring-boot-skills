// BAD — JPA entity used as the domain model, anemic, no invariant enforcement, no events

@Entity                                      // JPA — not part of the stack (Spring Data JDBC only)
@Data                                        // generates public setters — anyone can mutate state
@NoArgsConstructor                           // public no-arg constructor allows invalid objects
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;                         // IDs are bigint database identities (Long ≤ 2^53−1)

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;               // object reference across aggregates — reference by ID

    private String customerEmail;            // raw String — should be EmailAddress value object

    @Enumerated(EnumType.ORDINAL)            // ORDINAL breaks if enum is reordered;
    private OrderStatus status;              //   statuses are a lookup table with FK

    private double total;                    // binary floating point for money — use BigDecimal

    @OneToMany(mappedBy = "order")           // children loaded lazily, saved separately
    private List<OrderItem> items;           // not initialized — NPE risk

    // No behavior methods — all logic will live in service:
    // - No addItem() method
    // - No place() method
    // - No state transition validation
    // - No domain events
    // - Total is set manually from outside, not calculated

    // External code does:
    // order.getItems().add(new OrderItem(...));  // bypasses aggregate boundary
    // order.setStatus(OrderStatus.PLACED);       // no invariant check
    // order.setTotal(calculateTotal(items));      // logic outside domain
}
