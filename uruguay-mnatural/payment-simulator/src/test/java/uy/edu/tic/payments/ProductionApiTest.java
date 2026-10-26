package uy.edu.tic.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionApiTest {
  @Autowired MockMvc mvc;

  @Test
  void failureControlsAreAbsentInTheDefaultApplication() throws Exception {
    mvc.perform(
            post("/simulation/mode")
                .header("X-API-Key", "test-api-key")
                .contentType("application/json")
                .content("{\"mode\":\"UNAVAILABLE\"}"))
        .andExpect(status().isNotFound());
  }
}
