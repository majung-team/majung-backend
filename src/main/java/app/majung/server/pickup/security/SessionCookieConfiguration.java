package app.majung.server.pickup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Keep the existing cookie name and security policy when sessions move to JDBC. */
@Configuration(proxyBeanMethods = false)
class SessionCookieConfiguration {
    @Bean
    DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(secure);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }
}
