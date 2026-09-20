package com.cinema.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    @Bean
    MapReactiveUserDetailsService users(
            @Value("${cinema.security.admin-username}") String username,
            @Value("${cinema.security.admin-password}") String password) {

        var encoder = new BCryptPasswordEncoder();

        var admin = User.withUsername(username)
        		.password("{bcrypt}" + encoder.encode(password))
                .roles("ADMIN")
                .build();

        return new MapReactiveUserDetailsService(admin);
    }

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                // API za programske klijente, bez sesijske prijave.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .securityContextRepository(
                        NoOpServerSecurityContextRepository.getInstance()
                )
                .authorizeExchange(access -> access
                        .pathMatchers("/fallback/unavailable").permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/actuator/health",
                                "/api/movies", "/api/movies/**",
                                "/api/halls", "/api/halls/**",
                                "/api/screenings", "/api/screenings/**"
                        ).permitAll()
                        .pathMatchers("/api/**", "/actuator/info")
                        .hasRole("ADMIN")
                        .anyExchange().denyAll()
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}