package in.munimsaathi.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUrlsTest {

    @Test
    void aRailwayPostgresUrlBecomesAJdbcUrl() {
        assertThat(JdbcUrls.normalize("postgresql://postgres:secret@db.railway.internal:5432/railway"))
                .isEqualTo("jdbc:postgresql://postgres:secret@db.railway.internal:5432/railway");
        assertThat(JdbcUrls.normalize("postgres://postgres:secret@db.railway.internal:5432/railway"))
                .isEqualTo("jdbc:postgresql://postgres:secret@db.railway.internal:5432/railway");
        assertThat(JdbcUrls.normalize("jdbc:postgresql://localhost:5432/munim"))
                .isEqualTo("jdbc:postgresql://localhost:5432/munim");
    }

    @Test
    void quotedAndTrimmedUrlsAreAccepted() {
        assertThat(JdbcUrls.normalize("\"postgresql://postgres:secret@db.example.com:5432/postgres\""))
                .isEqualTo("jdbc:postgresql://postgres:secret@db.example.com:5432/postgres");
    }

    @Test
    void credentialsMoveOutOfTheJdbcUrl() {
        JdbcUrls.Parsed parsed = JdbcUrls.splitCredentials(
                "postgresql://postgres:secret@db.sotrqydhvhpjlzdzlyci.supabase.co:5432/postgres?sslmode=require"
        );
        assertThat(parsed.jdbcUrl())
                .isEqualTo("jdbc:postgresql://db.sotrqydhvhpjlzdzlyci.supabase.co:5432/postgres?sslmode=require");
        assertThat(parsed.username()).isEqualTo("postgres");
        assertThat(parsed.password()).isEqualTo("secret");
    }
}
