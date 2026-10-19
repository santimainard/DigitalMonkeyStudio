package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.dto.Forms.Registration;
import uy.edu.tic.platform.repository.Database;
import uy.edu.tic.platform.service.Accounts;

class RegistrationValidationTest {
  private Registration provider() {
    Registration f = new Registration();
    f.role = "PROVIDER";
    f.firstName = "Ana";
    f.lastName = "Pérez";
    f.email = "ana@example.com";
    f.password = "TestOnly123!";
    f.legalType = "LEGAL";
    f.legalName = "Empresa";
    f.taxId = "123456789012";
    f.contactEmail = "contacto@example.com";
    f.commercialName = "Empresa";
    f.phone = "1234";
    f.departmentId = 10L;
    f.localityId = 10L;
    f.locationDescription = "Montevideo";
    f.description = "Prueba";
    return f;
  }

  private Accounts accounts() {
    Database db = mock(Database.class);
    when(db.count("select count(*) from locality where id=? and department_id=?", 10L, 10L))
        .thenReturn(1L);
    return new Accounts(db, mock(PasswordEncoder.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sin-arroba", "ana@dominio", "@dominio.com", "ana..test@dominio.com", "ana@-dominio.com", "ana test@dominio.com"})
  void rejectsInvalidEmailsForBothFields(String value) {
    Registration f = provider();
    f.email = value;
    assertThrows(BusinessException.class, () -> accounts().register(f));
    f.email = "ana@example.com";
    f.contactEmail = value;
    assertThrows(BusinessException.class, () -> accounts().register(f));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "12345678901", "1234567890123", "12345678901a", "12345678901１", "12345678-012"})
  void rejectsInvalidRut(String value) {
    Registration f = provider();
    f.taxId = value;
    assertThrows(BusinessException.class, () -> accounts().register(f));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ana@example.com", "ana.perez+reservas@empresa.com.uy"})
  void acceptsValidProvider(String value) {
    Registration f = provider();
    f.email = value;
    f.contactEmail = value;
    assertDoesNotThrow(() -> accounts().register(f));
  }
}
