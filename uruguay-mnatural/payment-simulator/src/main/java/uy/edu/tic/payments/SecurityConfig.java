package uy.edu.tic.payments;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(c -> c.ignoringRequestMatchers("/payments", "/payments/**", "/simulation/**"))
        .build();
  }
}
