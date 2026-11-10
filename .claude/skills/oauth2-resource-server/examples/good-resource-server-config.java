// ✅ GOOD — stateless JWT resource server, one converter bean, values from configuration

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/products/**").hasAuthority("SCOPE_read")
                .requestMatchers(HttpMethod.POST, "/api/orders/**").hasAuthority("SCOPE_write")
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            .build();
        // No .cors(...): SPA, API and identity provider share one origin behind the proxy.
        // Issuer, JWK set and audience: spring.security.oauth2.resourceserver.jwt.* from env.
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(SecurityProps props) {
        // Defaults read the "scope" OR "scp" claim -> SCOPE_*. Setting the claim name to
        // "scope" would drop the scp fallback that Okta and Entra ID access tokens use.
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverter());
        converter.setPrincipalClaimName(props.principalClaim());   // from configuration
        return converter;
    }
}
