// ✅ GOOD (hand-written API only) — version declared on the mapping, resolved centrally.
// In a contract-first API the version is declared in the contract and controllers are generated.
@RestController
@RequestMapping("/api/orders")
final class OrderController {
    @GetMapping(path = "/{id}", version = "1.0")
    OrderV1 getV1(@PathVariable Long id) { return service.getV1(id); }
}
