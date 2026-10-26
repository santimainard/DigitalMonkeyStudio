package uy.edu.tic.payments;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, @Value("${simulator.api-key}") String key)
      throws Exception {
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected boolean shouldNotFilter(HttpServletRequest r) {
            return r.getServletPath().equals("/actuator/health")
                || r.getServletPath().equals("/error");
          }

          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws IOException, ServletException {
            String supplied = request.getHeader("X-API-Key");
            if (supplied == null
                || !MessageDigest.isEqual(
                    supplied.getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8))) {
              response.setStatus(401);
              response.setContentType("application/json");
              response.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
              return;
            }
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken("platform", null, List.of()));
            chain.doFilter(request, response);
          }
        };
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
