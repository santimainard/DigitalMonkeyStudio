package uy.edu.tic.payments;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Failure injection exists only in explicitly enabled integration-test environments. */
@RestController
@Profile("simulation-control")
public class SimulationControlController {
  private final PaymentStore store;

  public SimulationControlController(PaymentStore store) {
    this.store = store;
  }

  @PostMapping("/simulation/mode")
  Object mode(@RequestBody Map<String, String> body) {
    store.mode(body.get("mode"));
    return Map.of("mode", store.mode());
  }
}
