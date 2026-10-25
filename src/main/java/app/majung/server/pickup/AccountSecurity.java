package app.majung.server.pickup;

import java.util.List;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class AccountSecurity {
    @Bean
    @ConditionalOnProperty(name = "majung.login.enabled", havingValue = "true")
    ClientRegistrationRepository kakaoRegistration(
            @Value("${majung.login.kakao-client-id:}") String clientId,
            @Value("${majung.login.kakao-client-secret:}") String clientSecret,
            @Value("${majung.login.redirect-uri:}") String redirectUri,
            @Value("${majung.login.kakao-admin-key:}") String adminKey,
            @Value("${majung.login.kakao-app-id:}") String appId) {
        if (clientId.isBlank() || clientSecret.isBlank() || adminKey.isBlank() || appId.isBlank() || !redirectUri.matches("https://[^/?#]+/api/v1/auth/callback/kakao"))
            throw new IllegalArgumentException("Kakao login requires client credentials, unlink credentials and a fixed HTTPS callback");
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("kakao")
                .clientId(clientId).clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri).authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me").userNameAttributeName("id")
                .clientName("카카오").build());
    }

    @Bean
    @Order(1)
    SecurityFilterChain accountSecurityChain(HttpSecurity http, MemberService members,
            ObjectProvider<OAuth2AuthorizedClientService> authorizedClients,
            @Value("${majung.login.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher("/api/v1/auth/**", "/api/v1/accounts/**", "/api/v1/connections/**", "/api/v1/vehicle-links/**", "/api/v1/ride-requests/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/kakao/unlink", "/api/v1/auth/session", "/api/v1/auth/login", "/api/v1/auth/authorize/**", "/api/v1/auth/callback/**").permitAll()
                        .anyRequest().authenticated())
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":\"LOGIN_REQUIRED\",\"message\":\"로그인이 필요해요.\",\"requestId\":\"" + request.getAttribute("requestId") + "\"}");
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":\"ACCOUNT_REQUEST_REJECTED\",\"message\":\"페이지를 다시 연 뒤 시도해주세요.\",\"requestId\":\"" + request.getAttribute("requestId") + "\"}");
                        }))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        if (enabled) {
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build());
            requests.setReadTimeout(Duration.ofSeconds(5));
            RestTemplate userInfo = new RestTemplate(requests);
            userInfo.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
            DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
            delegate.setRestOperations(userInfo);
            RestClientAuthorizationCodeTokenResponseClient tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
            tokenClient.setRestClient(RestClient.builder().requestFactory(requests)
                    .messageConverters(converters -> {
                        converters.clear();
                        converters.add(new FormHttpMessageConverter());
                        converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                    }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/authorize"))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/callback/*"))
                    .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
                    .userInfoEndpoint(endpoint -> endpoint.userService(request -> {
                        OAuth2User identity = delegate.loadUser(request);
                        Object subject = identity.getAttribute("id");
                        Member member = members.login(subject == null ? null : subject.toString());
                        // No Kakao token, provider ID, email or profile image in the user session attributes.
                        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_MEMBER")),
                                Map.of("memberId", member.id.toString()), "memberId");
                    }))
                    .successHandler((request, response, authentication) -> {
                        OAuth2AuthorizedClientService clients = authorizedClients.getIfAvailable();
                        if (clients != null) clients.removeAuthorizedClient("kakao", authentication.getName());
                        response.setStatus(302);
                        response.setHeader("Location", "/account");
                    })
                    .failureHandler((request, response, exception) -> {
                        response.setStatus(302);
                        response.setHeader("Location", "/account?login=failed");
                    }));
        }
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/kakao/unlink"));
        // Only the provider webhook uses its server-side credential instead of browser CSRF.
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain pickupTokenSecurity(HttpSecurity http) throws Exception {
        // Existing vehicle/pickup APIs authenticate their explicit bearer tokens in their services.
        return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .securityContext(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).build();
    }
}
