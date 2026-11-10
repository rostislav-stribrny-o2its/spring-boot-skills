// ❌ BAD — hand-written versioned URL beside a generated contract-first API,
// bypassing both the contract and Framework 7 version mapping
@RestController
final class OrderController {
    @GetMapping("/api/v1/orders/{id}")
    Order getV1(@PathVariable Long id) { return service.get(id); }
}
