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

    @Test
    void anEncodedPasswordKeepsItsReservedCharacters() {
        JdbcUrls.Parsed parsed = JdbcUrls.splitCredentials(
                "postgresql://postgres.project:se%23cr%40et@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require"
        );
        assertThat(parsed.jdbcUrl())
                .isEqualTo("jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require");
        assertThat(parsed.username()).isEqualTo("postgres.project");
        assertThat(parsed.password()).isEqualTo("se#cr@et");
    }

    @Test
    void aPasswordWithARawHashStaysInsideTheUrl() {
        String url = "postgresql://postgres:se#cret@db.example.com:5432/postgres";
        JdbcUrls.Parsed parsed = JdbcUrls.splitCredentials(url);
        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:" + url);
        assertThat(parsed.username()).isNull();
        assertThat(parsed.password()).isNull();
    }
}
