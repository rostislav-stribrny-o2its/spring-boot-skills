package com.example.order.config;

import com.example.order.domain.Order;
import com.example.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.domain.EmailAddress;
import com.example.order.domain.Money;

import java.util.Currency;

/**
 * Development data only (sample orders). Reference data of lookup tables
 * (e.g. order_status) is NOT seeded here — it ships with the V migrations.
 */
@Component
@Profile("dev")   // only runs in the dev profile — never in TEST or PROD
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final OrderRepository orderRepository;

    public DevDataSeeder(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (orderRepository.count() > 0) {   // customers 1 and 2 come from the dev seed of customers
            log.info("Dev data already seeded — skipping");
            return;
        }

        Currency eur = Currency.getInstance("EUR");

        Order order1 = Order.create(1L, new EmailAddress("alice@example.test"), eur);
        order1.addItem(1001L, "Wireless Keyboard", 1, Money.of("79.99", "EUR"));
        order1.addItem(1002L, "USB-C Hub", 2, Money.of("34.99", "EUR"));
        order1.place();

        Order order2 = Order.create(2L, new EmailAddress("bob@example.test"), eur);
        order2.addItem(1003L, "Monitor Stand", 1, Money.of("129.99", "EUR"));

        orderRepository.save(order1);
        orderRepository.save(order2);

        log.info("Seeded {} dev orders", 2);
    }
}
