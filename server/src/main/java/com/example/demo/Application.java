package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.data.mongo.config.annotation.web.reactive.EnableMongoWebSession;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

}

@Configuration
@Slf4j
class WebConfig{
    @Bean
    @Order(-2)
    // see: https://stackoverflow.com/questions/47631243/spring-5-reactive-webexceptionhandler-is-not-getting-called
    // and https://docs.spring.io/spring-boot/docs/2.0.0.M7/reference/html/boot-features-developing-web-applications.html#boot-features-webflux-error-handling
    public WebExceptionHandler exceptionHandler(/*ObjectMapper objectMapper*/) {
        return (ServerWebExchange exchange, Throwable ex) -> {
            if (ex instanceof WebExchangeBindException) {
                WebExchangeBindException cvex = (WebExchangeBindException) ex;
                Map<String, String> errors = new HashMap<>();
                log.debug("errors:" + cvex.getFieldErrors());
                cvex.getFieldErrors().forEach(ev -> errors.put(ev.getField(), ev.getDefaultMessage()));

                log.debug("handled errors::" + errors);
                exchange.getResponse().setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON_UTF8);

                return exchange.getResponse().setComplete();
//                try {
//                    DataBuffer db = new DefaultDataBufferFactory().wrap(objectMapper.writeValueAsBytes(errors));
//                    exchange.getResponse().writeWith(Mono.just(db));
//                    exchange.getResponse().setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
//                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON_UTF8);
//
//                    return exchange.getResponse().setComplete();
//
//                } catch (JsonProcessingException e) {
//                    e.printStackTrace();
//                    return Mono.empty();
//                }
            } else if (ex instanceof PostNotFoundException) {
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                return exchange.getResponse().setComplete();
            }
            return Mono.empty();
        };
    }
}

@Configuration
@EnableMongoAuditing
class MongoConfig {

    @Bean
    public AuditorAware<Username> auditor() {
        return () -> ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .log()
            .filter(a -> a != null && a.isAuthenticated())
            .map(Authentication::getPrincipal)
            .cast(UserDetails.class)
            .map(auth -> new Username(auth.getName()))
            .switchIfEmpty(Mono.empty())
            .blockOptional();
    }
}

@Configuration
@EnableMongoWebSession
class SessionConfig {

    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        HeaderWebSessionIdResolver resolver = new HeaderWebSessionIdResolver();
        resolver.setHeaderName("X-AUTH-TOKEN");
        return resolver;
    }
}

@Configuration
class SecurityConfig {
    @Bean
    SecurityWebFilterChain springWebFilterChain(ServerHttpSecurity http) throws Exception {

        //@formatter:off
        return http
                    .csrf().disable()
                    .httpBasic().securityContextRepository(new WebSessionServerSecurityContextRepository())
                .and()
                    .authorizeExchange()
                    .pathMatchers(HttpMethod.GET, "/posts/**").permitAll()
                    .pathMatchers(HttpMethod.DELETE, "/posts/**").hasRole("ADMIN")
                    .pathMatchers("/posts/**").authenticated()
                    .pathMatchers("/user").authenticated()
                    .pathMatchers("/users/{user}/**").access(this::currentUserMatchesPath)
                    .anyExchange().permitAll()
                .and()
                    .build();
        //@formatter:on
    }

    private Mono<AuthorizationDecision> currentUserMatchesPath(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
            .map(a -> context.getVariables().get("user").equals(a.getName()))
            .map(AuthorizationDecision::new);
    }

    @Bean
    public ReactiveUserDetailsService userDetailsService(UserRepository users) {
        return (username) -> users.findByUsername(username)
            .map(u -> User.withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities(u.getRoles().toArray(new String[0]))
                .accountExpired(!u.isActive())
                .credentialsExpired(!u.isActive())
                .disabled(!u.isActive())
                .accountLocked(!u.isActive())
                .build()
            );
    }
}
