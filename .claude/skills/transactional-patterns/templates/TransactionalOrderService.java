package com.example.order.service;

import com.example.order.service.command.CreateOrderCommand;   // domain input, mapped from the generated …Dto
import com.example.order.domain.Order;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // default: all public methods are read-only
public class TransactionalOrderService {

    private final OrderRepository orderRepository;
    private final OrderAuditWriter auditWriter;

    // Read-only hint; replica routing requires separate infrastructure.
    public Order findById(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new OrderNotFoundException(id));
    }

    // Inherits class-level readOnly = true
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    // Override: write transaction with explicit rollback for checked exceptions
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(CreateOrderCommand command) {
        Order order = Order.create(command.customerId(), command.email(), command.currency());
        order = orderRepository.save(order);
        auditWriter.publishOrderCreated(order);
        return order;
    }

    // Override: write transaction
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        Order order = findById(id);
        order.cancel("cancelled on request");
        auditWriter.publishOrderCancelled(order);
    }
}

/**
 * Success audit entries must commit or roll back with the business operation.
 * MANDATORY prevents accidental use outside its transaction.
 * This template requires the application's domain, repository and audit types.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OrderAuditWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderCreated(Order order) {
        auditLogRepository.save(AuditLog.orderCreated(order));
        log.debug("Creation audit staged for order {}", order.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOrderCancelled(Order order) {
        auditLogRepository.save(AuditLog.orderCancelled(order));
        log.debug("Cancellation audit staged for order {}", order.getId());
    }
}
