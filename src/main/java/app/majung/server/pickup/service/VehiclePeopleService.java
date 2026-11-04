package app.majung.server.pickup.service;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A vehicle acts only for its explicitly linked driver; IDs from the client never select that driver. */
@Service
@Transactional
public class VehiclePeopleService {
    private final VehicleRegistrationService registration;
    private final VehicleAccountLinkRepository links;
    private final MemberService members;

    public VehiclePeopleService(VehicleRegistrationService registration, VehicleAccountLinkRepository links, MemberService members) {
        this.registration = registration;
        this.links = links;
        this.members = members;
    }
    public List<AccountApi.SearchResult> search(String authorization, String query, int page) {
        return members.search(driver(authorization), query, page);
    }
    public List<AccountApi.Connection> connections(String authorization, int page) {
        return members.connections(driver(authorization), page);
    }
    public AccountApi.Connection request(String authorization, UUID target, String expectedDriver) {
        return members.request(confirmedDriver(authorization, expectedDriver), target);
    }
    public AccountApi.Connection change(String authorization, UUID id, String action, String expectedDriver) {
        return members.change(confirmedDriver(authorization, expectedDriver), id, action);
    }
    private UUID confirmedDriver(String authorization, String expected) {
        UUID id = driver(authorization);
        if (!members.current(id).majungId.equals(expected))
            throw new ApiException(409, "DRIVER_ACCOUNT_CHANGED", "운전자 계정이 바뀌었어요. 다시 확인해주세요.");
        return id;
    }
    private UUID driver(String authorization) {
        RegisteredVehicle vehicle = registration.requireActive(authorization);
        VehicleAccountLink link = links.findById(vehicle.id).orElseThrow(() ->
                new ApiException(409, "DRIVER_ACCOUNT_REQUIRED", "차량에 운전자 계정을 먼저 연결해주세요."));
        return link.member.id;
    }
}
