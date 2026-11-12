---
name: be-oauth2-resource-server
description: >
  Use when configuring Spring Boot as an OAuth2 resource server, validating JWTs from
  an external identity provider (Keycloak, Entra ID, Auth0, Okta), extracting claims,
  mapping authorities from the token or from the application database, configuring
  issuer/audience/CORS from the environment, or implementing method-level authorization.
---

# OAuth2 Resource Server

Spring Boot 4.x ships **Spring Security 7** — lambda DSL only; `and()`, `authorizeRequests()`,
`antMatchers()`, and `AntPathRequestMatcher`/`MvcRequestMatcher` are gone
(`requestMatchers("/path/**")` is backed by `PathPatternRequestMatcher`).

Principles for the Elysium Java base:
- The backend is a **stateless resource server**. It never issues tokens, never shows a login
  form and never stores passwords — the identity provider does that.
- The backend is **independent of the token issuer**: issuer, JWK set, audience and the claim
  that identifies the person come from configuration (environment variables), never from
  constants in code. Switching Keycloak for another OIDC provider is a configuration change.
- **One place** turns a token into authorities: a single converter bean. Claim normalization
  (nested Keycloak roles, `scope` vs `scp`) happens there and nowhere else.

## Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

## Security Configuration

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter,
                                           CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)   // stateless API with bearer tokens, no cookies
            .cors(cors -> cors.configurationSource(corsConfigurationSource)) // no-op while no origins are configured
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
            )
            .build();
    }
}
```

## Where authorities come from — pick one model

| Model | Authorities | When |
|---|---|---|
| **Token roles** | scopes (`SCOPE_*`) and/or IdP roles (`ROLE_*`) from claims | the IdP is the master of who may do what |
| **Application roles** | token proves identity (and at most an entry role); business roles are loaded from the application database | the application manages roles itself (role administration in the app, roles derived from business data) |

Exactly one `Converter<Jwt, AbstractAuthenticationToken>` bean exists; the two snippets below are alternatives — choosing a model means replacing that one bean.

Do not mix both models for the same decision. If business roles live in the database, a role
claim in the token must not grant them as well.

### Token roles (Keycloak realm roles + scopes)

```java
@Bean
public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter(SecurityProps props) {
    // Keep the defaults: the "scope" OR "scp" claim (Okta/Entra use scp) -> SCOPE_*.
    // Calling setAuthoritiesClaimName(...) replaces them — then hasAuthority("SCOPE_admin")
    // can never pass. Extend the defaults with Keycloak realm roles instead.
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName(props.principalClaim());   // e.g. "sub", from configuration
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        // Keycloak nests realm roles under realm_access.roles -> ROLE_* (for hasRole)
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return authorities;
    });
    return converter;
}
```

### Application roles (loaded from the database)

```java
@Bean
public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter(SecurityProps props,
                                                                     UserRoleService userRoles) {
    return jwt -> {
        String personKey = jwt.getClaimAsString(props.principalClaim());
        // Roles come from the application database; the token only identifies the person.
        // The lookup is cached briefly (seconds to minutes) so revocations take effect quickly.
        Collection<GrantedAuthority> authorities = userRoles.findRoles(personKey).stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        return new JwtAuthenticationToken(jwt, authorities, personKey);
    };
}
```

A person with a valid token but no record in the application gets no business authority;
every endpoint requires a concrete role, so "authenticated" alone opens nothing except an
explicit status endpoint.

## application.yml — values from the environment

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OIDC_ISSUER_URI}     # e.g. https://app.example.com/auth/realms/my-realm
          jwk-set-uri: ${OIDC_JWKS_URI}      # optional; avoids discovery at startup
          audiences: ${OIDC_AUDIENCE}        # without it, tokens minted for ANY client are accepted

app:
  security:
    principal-claim: ${OIDC_PRINCIPAL_CLAIM:sub}
    cors:
      allowed-origins: ${CORS_ALLOWED_ORIGINS:}   # empty = CORS disabled (same-origin deployment)
```

Bind `app.security.*` with a validated `@ConfigurationProperties` record (see the
`be-configuration-properties` skill). Secrets and per-environment URLs never appear in code
or in committed profile files.

## CORS

- **Preferred: same origin.** When a reverse proxy serves the SPA (`/`), the API (`/api`) and the
  identity provider (`/auth`) under one hostname, the browser never makes a cross-origin call
  and CORS stays **disabled**. Do not add a CORS configuration "just in case".
- **Only when the frontend really runs on another origin** (e.g. a local Vite dev server
  without a proxy), allow exactly the configured origins:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource(SecurityProps props) {
    var source = new UrlBasedCorsConfigurationSource();
    if (props.cors().allowedOrigins().isEmpty()) {
        return source;                                   // nothing registered = no CORS
    }
    var config = new CorsConfiguration();
    config.setAllowedOrigins(props.cors().allowedOrigins());   // exact origins, never "*"
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setMaxAge(3600L);
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

## Custom Claim Extraction

Keep claim access in one component (see `templates/JwtClaimExtractor.java`); services ask it
for the identity instead of reading claims themselves.

## Accessing the Current User

Use the exact `(iss, sub)` pair — or the configured principal claim together with the issuer —
as the external identity key, with a database unique constraint on that pair. Email and
username are optional, mutable profile attributes, not account lookup or automatic
account-linking keys, even when email is verified.
The [ExternalIdentity template](templates/ExternalIdentity.java) accepts an already
verified access token; signature, issuer, audience and expiry validation must happen first.
Allow only configured issuers; never discover an arbitrary issuer URL from an untrusted token.
For existing subject-only mappings, backfill the known issuer before introducing a second issuer.
Account linking requires an explicit authenticated flow proving control of both accounts.

See [OpenID claim stability](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability).

In a contract-first API the controller is generated; read the principal in the hand-written
delegate or service, not by changing the generated controller:

```java
@Service
public class MyOrdersQuery {
    private final JwtClaimExtractor claims;
    private final OrderReadRepository orders;

    public MyOrdersQuery(JwtClaimExtractor claims, OrderReadRepository orders) {
        this.claims = claims;
        this.orders = orders;
    }

    public List<OrderSummary> myOrders() {
        ExternalIdentity identity = claims.getIdentity();
        return orders.findByOwner(identity.issuer(), identity.subject());
    }
}
```

## Method Security

```java
@PreAuthorize("hasAuthority('SCOPE_orders:read')")
public List<Order> findAll() { ... }

@PreAuthorize("hasRole('ADMIN') or @orderSecurity.isOwner(#orderId, authentication)")
public Order findById(Long orderId) { ... }

// Custom security bean
@Component("orderSecurity")
public class OrderSecurityService {
    private final OrderReadRepository orderRepository;

    public OrderSecurityService(OrderReadRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean isOwner(Long orderId, Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        ExternalIdentity identity = ExternalIdentity.from(jwt);
        return orderRepository.existsByIdAndOwner(orderId, identity.issuer(), identity.subject());
    }
}
```

## Verification scope

The upstream repository fixture targets Boot 4.1.0 with Java 17/21 and checks signed-token
validation and issuer/subject mapping; it does not contact a production identity provider.
The Elysium base (JDK 25) has not re-run that fixture. Check the
[upstream coverage matrix](https://github.com/rrezartprebreza/spring-boot-skills/blob/main/verification/MATRIX.md)
before treating another minor version or an illustrative snippet as verified.

## Gotchas
- Agent hard-codes issuer, JWK URL, audience or CORS origins — all come from environment variables
- Agent enables CORS with `localhost:3000` or `*` in a same-origin deployment — leave CORS disabled unless the frontend really runs on another origin
- Agent maps business roles from the token AND from the database — pick one model; with application roles the token only identifies the person
- Agent normalizes claims in several places (controller, service, filter) — one converter bean, one claim extractor
- Agent finds or merges accounts by email — use the verified issuer/subject pair (or the configured principal claim); email changes must not change ownership
- Agent calls `setAuthoritiesClaimName(...)` on `JwtGrantedAuthoritiesConverter` — it replaces the default `scope`/`scp` lookup, so `SCOPE_*` authorities vanish (and Okta/Entra `scp` scopes are dropped); extend the defaults instead
- Agent parses `jwt.getSubject()` with `UUID.fromString` — Auth0 (`auth0|...`) and Okta (`00u...`) subjects aren't UUIDs; the request fails with 500
- Agent omits `audiences` — the resource server then accepts any token the issuer minted for any client
- Agent uses `hasRole("ADMIN")` for scope check — scopes use `hasAuthority("SCOPE_admin")`
- Agent forgets `issuer-uri` validation — always configure to prevent token forgery
- Agent maps roles wrong for Keycloak — roles are nested under `realm_access.roles`
- Agent uses `getPrincipal()` directly — cast to `Jwt` or use `@AuthenticationPrincipal Jwt`
- Agent adds `userDetailsService` bean or a login form — not needed for resource servers (stateless JWT)
- Agent adds the Boot 3 starter `spring-boot-starter-oauth2-resource-server` — Boot 4 renamed security starters; use `spring-boot-starter-security-oauth2-resource-server`
- Agent uses non-lambda chaining (`.oauth2ResourceServer().jwt()`, `.and()`) — removed in Security 7, won't compile; lambda DSL only
- Agent writes `antMatchers()` / `AntPathRequestMatcher` — removed in Security 7; use `requestMatchers("/path/**")` (backed by `PathPatternRequestMatcher`)
- Agent mocks `JwtDecoder` with `@MockBean` in tests — removed in Boot 4; use `@MockitoBean` (and `@AutoConfigureMockMvc` — `@SpringBootTest` no longer provides `MockMvc`)

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, Spring Security 7).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/oauth2-resource-server`, MIT License, © Rrezart Prebreza.
Changes: issuer/audience/principal claim and CORS from the environment, same-origin default
without CORS, token roles vs. application roles, `Long` IDs, controller example replaced by a
service reading the identity (generated controllers).
