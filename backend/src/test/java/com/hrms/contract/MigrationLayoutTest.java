package com.hrms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Flyway files must sit in their module folder and inside its reserved version range. */
class MigrationLayoutTest {

    private static final Path ROOT = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Map<String, int[]> RANGES = Map.of(
            "", new int[] {1, 199},
            "employee", new int[] {200, 299},
            "payroll", new int[] {300, 399},
            "performance", new int[] {400, 499},
            "audit", new int[] {500, 599},
            "reporting", new int[] {600, 699});

    @Test
    void migrationsAreInTheirReservedRange() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(".gitkeep"))
                    .toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            Path rel = ROOT.relativize(file);
            String folder = rel.getParent() == null ? "" : rel.getParent().toString();
            assertThat(RANGES).as("unexpected migration folder for %s", rel).containsKey(folder);
            Matcher m = NAME.matcher(file.getFileName().toString());
            assertThat(m.matches()).as("name must be V<n>__lower_snake.sql: %s", rel).isTrue();
            int version = Integer.parseInt(m.group(1));
            int[] range = RANGES.get(folder);
            assertThat(version).as("%s outside V%d-V%d", rel, range[0], range[1]).isBetween(range[0], range[1]);
        }
    }
}
