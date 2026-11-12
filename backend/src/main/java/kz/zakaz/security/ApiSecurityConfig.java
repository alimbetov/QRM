package kz.zakaz.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Fail-closed default. Without a configured trusted JwtDecoder, all routes are
 * denied; no ad-hoc dev tokens or permissive shortcuts.
 */
@Configuration
public class ApiSecurityConfig {
    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               ObjectProvider<JwtDecoder> decoders) throws Exception {
        http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        var decoder=decoders.getIfAvailable();
        if(decoder==null) {
            http.authorizeHttpRequests(auth->auth.anyRequest().denyAll());
        } else {
            http.authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/listings/*/publish")
                    .hasAuthority("SCOPE_catalog:write")
                .anyRequest().denyAll())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(decoder)));
        }
        return http.build();
    }
}
