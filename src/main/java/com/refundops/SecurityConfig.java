package com.refundops;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.util.StringUtils;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${REFUNDS_DAHNESH_PASSWORD:}") String dahneshPassword,
            @Value("${REFUNDS_SHWETA_PASSWORD:}") String shwetaPassword,
            PasswordEncoder encoder) {
        requirePassword("REFUNDS_DAHNESH_PASSWORD", dahneshPassword);
        requirePassword("REFUNDS_SHWETA_PASSWORD", shwetaPassword);
        return new InMemoryUserDetailsManager(
                User.withUsername("dahnesh").password(encoder.encode(dahneshPassword))
                        .roles("REQUESTOR", "APPROVER").build(),
                User.withUsername("shweta").password(encoder.encode(shwetaPassword))
                        .roles("REQUESTOR").build());
    }

    private static void requirePassword(String variable, String password) {
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("Set the " + variable
                    + " environment variable to a non-blank demo password before starting RefundOps.");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.authorizeRequests()
                .antMatchers("/", "/index.html", "/favicon.ico", "/error", "/css/**", "/js/**",
                        "/assets/**", "/images/**", "/*.css", "/*.js", "/api/session").permitAll()
                .antMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/refunds/*/approve", "/api/refunds/*/reject").hasRole("APPROVER")
                .antMatchers(org.springframework.http.HttpMethod.POST, "/api/refunds", "/api/demo/reset")
                        .hasRole("REQUESTOR")
                .antMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
                .and()
                .requestCache().disable()
                .formLogin()
                    .loginPage("/")
                    .loginProcessingUrl("/login")
                    .successHandler((request, response, authentication) ->
                            write(mapper, response, 200, Map.of("success", true)))
                    .failureHandler((request, response, exception) ->
                            write(mapper, response, 401,
                                    ApiErrors.body("INVALID_CREDENTIALS", "Invalid username or password.")))
                    .permitAll()
                .and()
                .logout()
                    .logoutUrl("/logout")
                    .logoutSuccessHandler((request, response, authentication) ->
                            write(mapper, response, 200, Map.of("success", true)))
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
                .and()
                .exceptionHandling()
                    .authenticationEntryPoint((request, response, exception) ->
                            write(mapper, response, 401,
                                    ApiErrors.body("UNAUTHENTICATED", "Sign in to continue.")))
                    .accessDeniedHandler((request, response, exception) -> {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                        boolean anonymous = auth == null || !auth.isAuthenticated()
                                || auth instanceof AnonymousAuthenticationToken;
                        if (anonymous && request.getServletPath().startsWith("/api/")
                                && !"/api/session".equals(request.getServletPath())) {
                            write(mapper, response, 401,
                                    ApiErrors.body("UNAUTHENTICATED", "Sign in to continue."));
                        } else if (exception instanceof CsrfException) {
                            write(mapper, response, 403, ApiErrors.body("CSRF_INVALID",
                                    "Missing or invalid CSRF token. Refresh /api/session and retry."));
                        } else {
                            write(mapper, response, 403, ApiErrors.body("ACCESS_DENIED",
                                    "You do not have permission to perform this action."));
                        }
                    });
        return http.build();
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }
}
