package app.memoria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(@Value("${memoria.data-dir}") String dataDir) throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("memoria.db"));
        return dataSource;
    }
}
