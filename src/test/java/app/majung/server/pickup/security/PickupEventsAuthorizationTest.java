package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class PickupEventsAuthorizationTest {
    @Test void accountStreamStopsWhenSessionEndsOrChangesAccount() {
        UUID member = UUID.randomUUID(), requestId = UUID.randomUUID();
        var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new DefaultOAuth2User(roles, Map.of("id", member.toString()), "id");
        var authentication = new OAuth2AuthenticationToken(principal, roles, "kakao");
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        var request = new MockHttpServletRequest(); request.setSession(session);
        var rides = mock(RideRequestService.class);
        var streams = mock(PickupEventStreams.class);
        var captured = new AtomicReference<Supplier<PickupEventStreams.State>>();
        when(streams.open(eq(requestId), any())).thenAnswer(call -> {
            captured.set(call.getArgument(1)); return new SseEmitter();
        });
        var snapshot = mock(PickupApi.Snapshot.class); var vehicle = mock(PickupApi.VehicleState.class);
        when(rides.tracking(member, requestId)).thenReturn(new RideRequestApi.Tracking(snapshot, vehicle));
        new PickupEventsApi(mock(PickupService.class), rides, streams).account(
                requestId, authentication, request, new MockHttpServletResponse());
        assertSame(snapshot, captured.get().get().pickup());
        context.setAuthentication(null);
        assertEquals(401, assertThrows(ApiException.class, () -> captured.get().get()).status);
        context.setAuthentication(authentication);
        session.invalidate();
        assertEquals(401, assertThrows(ApiException.class, () -> captured.get().get()).status);
        verify(rides, times(1)).tracking(member, requestId);
    }
}
