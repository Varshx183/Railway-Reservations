package dev.railway;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
class MigrationIntegrationTest {
    @Test void upgradePreservesExistingV1BookingsAndIsRepeatable() throws Exception {
        String schema="railway_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url=System.getenv("TEST_DB_URL"),user=System.getenv().getOrDefault("TEST_DB_USER","railway"),password=System.getenv("TEST_DB_PASSWORD");
        try(Connection base=DriverManager.getConnection(url,user,password);Statement query=base.createStatement()){query.execute("CREATE SCHEMA " + schema);}
        Config config=new Config(url + (url.contains("?")?"&":"?") + "currentSchema=" + schema,user,password,"migration-test-admin","127.0.0.1",0,8,32,1000);
        Database database=new Database(config);
        try {
            // Build a true v1 schema, then insert a booking before any v2 migration.
            try(Connection connection=database.open();Statement query=connection.createStatement();var resource=Database.class.getResourceAsStream("/database/001_schema.sql")) {
                query.execute(new String(resource.readAllBytes(),StandardCharsets.UTF_8));
                query.execute("SELECT release_train(6773,'2030-03-08',1,0)");
                query.execute("SELECT book_tickets(6773,'2030-03-08','AC',ARRAY['Existing Passenger'])");
            }
            database.initialize(false);database.initialize(false);
            try(Connection connection=database.open();Statement query=connection.createStatement();ResultSet result=query.executeQuery("SELECT status, owner_id, passenger_name, active FROM tickets JOIN passengers USING(pnr)")) {
                assertTrue(result.next());assertEquals("CONFIRMED",result.getString(1));assertNull(result.getObject(2));
                assertEquals("Existing Passenger",result.getString(3));assertTrue(result.getBoolean(4));
            }
            var next=database.book(6773,LocalDate.of(2030,3,8),"AC",List.of("New Passenger"));
            assertEquals(2,next.path("passengers").get(0).path("berth").asInt());
        } finally {
            try(Connection base=DriverManager.getConnection(url,user,password);Statement query=base.createStatement()){query.execute("DROP SCHEMA " + schema + " CASCADE");}
        }
    }
}
