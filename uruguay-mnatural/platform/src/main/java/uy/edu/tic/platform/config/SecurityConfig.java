package uy.edu.tic.platform.config;

import java.util.Locale;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import uy.edu.tic.platform.repository.Database;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(Database db) {
    return email -> {
      var u =
          db.optional(
                  "select * from app_user where email=?", email.strip().toLowerCase(Locale.ROOT))
              .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
      return User.withUsername(u.get("email").toString())
          .password(u.get("password_hash").toString())
          .roles(u.get("role").toString())
          .disabled(!Boolean.TRUE.equals(u.get("active")))
          .build();
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain adminApi(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/v1/admin/**")
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("ADMIN"))
        .httpBasic(b -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain web(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/register",
                        "/login",
                        "/css/**",
                        "/js/**",
                        "/error",
                        "/actuator/health",
                        "/openapi.yaml")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/activities/**", "/api/v1/ventures", "/api/v1/payment-events")
                    .permitAll()
                    .requestMatchers("/")
                    .authenticated()
                    .requestMatchers("/explore", "/activities/**")
                    .hasRole("TOURIST")
                    .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/provider/reapply")
                    .denyAll()
                    .requestMatchers("/provider/**")
                    .hasRole("PROVIDER")
                    .requestMatchers("/tourist/**", "/api/v1/reservations/**")
                    .hasRole("TOURIST")
                    .anyRequest()
                    .authenticated())
        .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/dashboard", true).permitAll())
        .logout(l -> l.logoutSuccessUrl("/"))
        .csrf(c -> c.ignoringRequestMatchers("/api/v1/payment-events"))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setCharacterEncoding("UTF-8");
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Iniciá sesión\"}");
                        },
                        req -> req.getRequestURI().startsWith("/api/"))
                    .defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication
                            .LoginUrlAuthenticationEntryPoint("/login"),
                        req -> !req.getRequestURI().startsWith("/api/")));
    return http.build();
  }
}
