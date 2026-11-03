package app.majung.server.pickup;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SwaggerConfig {

    private static final String BEARER_TOKEN = "bearerAuth";
    private static final String SESSION_COOKIE = "sessionCookie";

    @Bean
    OpenAPI majungOpenAPI(@Value("${majung.swagger.server-url:}") String serverUrl) {
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("마중 (Majung) API 명세서")
                        .description("""
                                ### 차량-탑승자 실시간 픽업 플랫폼 '마중(Majung)' 백엔드 API
                                
                                - **인증 체계**:
                                  - 탑승자/운전자 계정: 카카오 OAuth2 로그인 세션 쿠키 (`JSESSIONID`)
                                  - 픽업 초대/세션 토큰: Bearer Token (`Authorization: Bearer <token>`)
                                  - 차량 기기 인증: 차량 전용 토큰
                                - **실시간 이벤트**:
                                  - SSE 엔드포인트: `/api/v1/pickups/{pickupId}/events`
                                """)
                        .version("v1.0.0")
                        .contact(new Contact().name("Majung Team")));

        if (serverUrl != null && !serverUrl.isBlank()) {
            openApi.addServersItem(new Server().url(serverUrl).description("지정 서버"));
        } else {
            openApi.addServersItem(new Server().url("https://majung.cloud").description("운영 서버 (GCP)"));
            openApi.addServersItem(new Server().url("http://localhost:8080").description("로컬 개발 서버"));
        }

        return openApi
                .components(new Components()
                        .addSecuritySchemes(BEARER_TOKEN, new SecurityScheme()
                                .name("Authorization")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT/Token")
                                .description("픽업 초대 토큰 또는 차량 기기 토큰"))
                        .addSecuritySchemes(SESSION_COOKIE, new SecurityScheme()
                                .name("JSESSIONID")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .description("카카오 로그인 후 발급되는 세션 쿠키")))
                .addSecurityItem(new SecurityRequirement()
                        .addList(BEARER_TOKEN)
                        .addList(SESSION_COOKIE));
    }
}
