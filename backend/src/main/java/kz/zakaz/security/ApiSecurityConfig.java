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
            http.authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog","/api/v1/public/catalog/categories").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/search","/api/v1/public/search/suggest").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/feed").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/*/media","/api/v1/public/media/*").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/q/*").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/public/q/*/leads").permitAll()
                .anyRequest().denyAll());
        } else {
            http.authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog","/api/v1/public/catalog/categories").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/search","/api/v1/public/search/suggest").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/feed").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/catalog/*/media","/api/v1/public/media/*").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/public/q/*").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/public/q/*/leads").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/buyers/me")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/buyers/enroll")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.GET,"/api/v1/buyers/orders/*")
                    .hasAuthority("SCOPE_order:read")
                .requestMatchers(HttpMethod.POST,"/api/v1/buyers/demands")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.GET,"/api/v1/buyers/demands/*/bids")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/buyers/demands/*/bids/*/accept")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/buyers/orders/*/cancel")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.PUT,"/api/v1/buyers/demands/*/invitation-sharing")
                    .hasAuthority("SCOPE_deal:write")
                .requestMatchers(HttpMethod.PUT,"/api/v1/sellers/*/invitation-preference")
                    .hasAuthority("SCOPE_deal:bid")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/invitations")
                    .hasAuthority("SCOPE_deal:bid")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/invitations/*/quote-context")
                    .hasAuthority("SCOPE_deal:bid")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/invitations/*/bid")
                    .hasAuthority("SCOPE_deal:bid")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/demands/*/bids")
                    .hasAuthority("SCOPE_deal:bid")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/me")
                    .hasAuthority("SCOPE_order:confirm")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/orders")
                    .hasAuthority("SCOPE_order:confirm")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/orders/*/confirm")
                    .hasAuthority("SCOPE_order:confirm")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/orders/*/cancel")
                    .hasAuthority("SCOPE_order:confirm")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/leads")
                    .hasAuthority("SCOPE_leads:read")
                .requestMatchers(HttpMethod.PUT,"/api/v1/sellers/*/leads/*/status")
                    .hasAuthority("SCOPE_leads:read")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/listings")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/listings/*")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/listings")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.GET,"/api/v1/sellers/*/listings/*/qr.png")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.PUT,"/api/v1/sellers/*/listings/*/skus/*/price")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/listings/*/media")
                    .hasAuthority("SCOPE_catalog:write")
                .requestMatchers(HttpMethod.POST,"/api/v1/sellers/*/listings/*/publish")
                    .hasAuthority("SCOPE_catalog:write")
                .anyRequest().denyAll())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(decoder)));
        }
        return http.build();
    }
}
