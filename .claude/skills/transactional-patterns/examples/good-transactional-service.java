// ✅ GOOD — business mutation and success audit commit or roll back together.
// Illustrative: imports and domain types are supplied by the application.

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderAuditService auditService;  // separate bean to avoid self-invocation

    public Order findById(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(CreateOrderCommand command) {
        Order order = Order.create(command.customerId(), command.email(), command.currency());
        order = orderRepository.save(order);
        auditService.logCreation(order);  // same transaction; no success record on rollback
        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        Order order = orderRepository.findById(id)
            .orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel("cancelled on request");
        auditService.logCancellation(order);
    }
}

@Service
@RequiredArgsConstructor
public class OrderAuditService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void logCreation(Order order) {
        auditLogRepository.save(AuditLog.orderCreated(order));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void logCancellation(Order order) {
        auditLogRepository.save(AuditLog.orderCancelled(order));
    }
}
