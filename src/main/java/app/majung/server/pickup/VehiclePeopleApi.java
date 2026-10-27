package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/people")
class VehiclePeopleApi {
    record Search(List<AccountApi.SearchResult> items) { }
    record Connections(List<AccountApi.Connection> items) { }
    record Result(AccountApi.Connection connection) { }
    record Target(@NotNull UUID memberId, @NotBlank String driverMajungId) { }
    record Driver(@NotBlank String driverMajungId) { }
    private final VehiclePeopleService service;
    VehiclePeopleApi(VehiclePeopleService service) { this.service = service; }

    @GetMapping("/search")
    Search search(@RequestHeader(value="Authorization", required=false) String authorization,
                  @RequestParam String query, @RequestParam(defaultValue="0") int page) {
        return new Search(service.search(authorization, query, page));
    }
    @GetMapping("/connections")
    Connections connections(@RequestHeader(value="Authorization", required=false) String authorization,
                            @RequestParam(defaultValue="0") int page) {
        return new Connections(service.connections(authorization, page));
    }
    @PostMapping("/connections")
    Result request(@RequestHeader(value="Authorization", required=false) String authorization,
                   @Valid @RequestBody Target target) {
        return new Result(service.request(authorization, target.memberId(), target.driverMajungId()));
    }
    @PostMapping("/connections/{id}/{action}")
    Result change(@RequestHeader(value="Authorization", required=false) String authorization,
                  @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Driver driver) {
        return new Result(service.change(authorization, id, action, driver.driverMajungId()));
    }
}
