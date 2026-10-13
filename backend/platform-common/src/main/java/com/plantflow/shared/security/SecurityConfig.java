package com.plantflow.shared.security;

import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter=new JwtAuthenticationConverter();converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object access=jwt.getClaims().get("realm_access");
            if(!(access instanceof Map<?,?> realm) || !(realm.get("roles") instanceof List<?> roles)) return List.of();
            return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(Set.of("ADMIN","DISPATCHER","DRIVER","EMPLOYEE","DEVICE")::contains).<org.springframework.security.core.GrantedAuthority>map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList();
        });
        // Bearer-only APIs never authenticate via cookies; disabling CSRF is limited to this stateless chain.
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll().requestMatchers("/api/tracking/device/positions").hasRole("DEVICE").anyRequest().hasAnyRole("ADMIN","DISPATCHER","DRIVER","EMPLOYEE"))
            .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter)))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .build();
    }
}
