package app.majung.server.pickup.api;

import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.service.MemberService;
import app.majung.server.pickup.service.VehicleAccountService;
import app.majung.server.pickup.service.VehicleEnrollmentService;
import app.majung.server.pickup.service.VehicleRegistrationService;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-enrollment;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql,classpath:db/legacy-required-vehicle-code.sql",
        "majung.rate-limit.enabled=false",
        "majung.login.unlink-cleanup-enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class VehicleEnrollmentLegacySchemaTest {
    @Autowired private VehicleEnrollmentService enrollments;
    @Autowired private VehicleRegistrationService registration;
    @Autowired private VehicleAccountService links;
    @Autowired private MemberService members;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void retiredRequiredCodeBlocksEnrollmentUntilMigrationWithoutDeletingOldCodes() {
        UUID oldVehicleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO registered_vehicle
                    (id, version, credential_hash, display_name, registered_at, vehicle_code)
                VALUES (?, 0, ?, ?, CURRENT_TIMESTAMP, ?)
                """, oldVehicleId, "legacy-credential-hash", "기존 차량", "OLD-CODE");
        Member driver = members.login("98000001");
        members.nickname(driver.id, "가상운전자");
        var first = enrollments.start();
        String authorization = "Bearer " + first.deviceToken();
        enrollments.claim(driver.id, first.enrollmentId(), first.code());

        assertThrows(DataIntegrityViolationException.class,
                () -> enrollments.confirm(first.enrollmentId(), authorization, driver.majungId));
        assertEquals("AWAITING_VEHICLE_CONFIRMATION",
                enrollments.device(first.enrollmentId(), authorization).status());
        assertTrue(links.links(driver.id).isEmpty());

        var migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/relax-retired-vehicle-code.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertEquals("LINKED", enrollments.confirm(first.enrollmentId(), authorization, driver.majungId).status());
        assertEquals(first.enrollmentId(), registration.current(authorization).vehicleId());
        assertEquals(1, links.links(driver.id).size());
        var code = registration.issueCode();
        registration.register(new VehicleRegistrationApi.Registration(code.code(), "두 번째 새 차량"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM registered_vehicle WHERE vehicle_code IS NULL", Integer.class));
        assertEquals("OLD-CODE", jdbc.queryForObject(
                "SELECT vehicle_code FROM registered_vehicle WHERE id = ?", String.class, oldVehicleId));
    }
}
