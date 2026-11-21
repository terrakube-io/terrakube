package io.terrakube.api;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class DemoChangelogFinopsTest {

    @Test
    void demoChangelogLoadsSimpleFinopsOrganizationWithAllRequiredEntities() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:finops_demo_test;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/changelog-demo.xml",
                    new ClassLoaderResourceAccessor(),
                    database)) {
                liquibase.update("");
            }
        }

        try (Connection verifyConn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            assertCount(verifyConn, "SELECT COUNT(*) FROM organization WHERE name = 'simple-finops'", 1);
            assertCount(verifyConn,
                    "SELECT COUNT(*) FROM team WHERE organization_id = '71000000-0000-0000-0000-000000000001'",
                    3);
            assertCount(verifyConn,
                    "SELECT COUNT(*) FROM workspace WHERE organization_id = '71000000-0000-0000-0000-000000000001'",
                    7);
            assertCount(verifyConn,
                    "SELECT COUNT(*) FROM policy_set WHERE organization_id = '71000000-0000-0000-0000-000000000001'",
                    4);
            assertCount(verifyConn,
                    "SELECT COUNT(*) FROM policy_exemption WHERE organization_id = '71000000-0000-0000-0000-000000000001'",
                    1);
            assertCount(verifyConn,
                    "SELECT COUNT(*) FROM policy_set_parameter WHERE policy_set_id = '71000000-0000-0000-0000-000000000043'",
                    1);
        }
    }

    private static void assertCount(Connection conn, String sql, int expected) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(expected);
        }
    }
}
