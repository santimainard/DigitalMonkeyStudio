package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import uy.edu.tic.platform.dto.*;

class RegistrationChecksTest {
  @Test
  void phonesAcceptInternationalFormattingButRejectLettersAndImplausibleLengths() {
    for (String phone : new String[] {"099123456", "(099) 123-456", "+598 (99) 123-456", "123456"})
      assertTrue(RegistrationChecks.validPhone(phone));
    for (String phone :
        new String[] {
          "abc123456", "099CALLME", "123", "1234567890123456", "++59899123456", "  ", "１２３４５６"
        }) assertFalse(RegistrationChecks.validPhone(phone));
  }

  @Test
  void emailsRequireAnAtSignAndACompleteDomain() {
    for (String email :
        new String[] {"maria@example.test", "maria+viajes@example.com", " O.Neill@example.com "})
      assertTrue(RegistrationChecks.validEmail(email));
    for (String email :
        new String[] {
          "correo",
          "correo@example",
          "a@@example.com",
          "a b@example.com",
          "a@.com",
          "a@example..com"
        }) assertFalse(RegistrationChecks.validEmail(email));
  }

  @Test
  void sharedRulesReportAllFieldsAndMatchPasswordsWithoutTrimming() {
    var f = new Forms.Registration();
    f.firstName = "Ana123";
    f.lastName = "Pérez";
    f.email = "sin-arroba";
    f.password = "TestOnly123!";
    f.passwordConfirmation = "TestOnly123! ";
    var errors = RegistrationChecks.errors(f);
    assertTrue(errors.containsKey("firstName"));
    assertTrue(errors.containsKey("email"));
    assertTrue(errors.containsKey("passwordConfirmation"));
    assertTrue(errors.containsKey("document"));
    assertFalse(errors.containsKey("phone"));
    assertFalse(errors.containsKey("taxId"));
    f.firstName = "María-José";
    f.lastName = "O’Neill";
    f.email = "maria@example.test";
    f.passwordConfirmation = f.password;
    f.document = "ABC-123";
    assertTrue(RegistrationChecks.errors(f).isEmpty());
  }
}
