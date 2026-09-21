package com.acme.hrms.tools.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * hrms-tool-cdc-sync.jar must be runnable with {@code java -jar} like the parallel-run and
 * reconcile tool jars: a Main-Class manifest pointing at {@link CdcSyncMain} and a lib/ classpath.
 */
class ExecutableJarManifestTest {

  private static Path pom() {
    Path p = Path.of("pom.xml");
    return Files.exists(p) ? p : Path.of("tools/cdc-sync/pom.xml");
  }

  @Test
  void jarManifestDeclaresCdcSyncMainAsMainClass() throws Exception {
    String pom = Files.readString(pom());
    Matcher m = Pattern.compile("<mainClass>([^<]+)</mainClass>").matcher(pom);
    assertThat(m.find()).as("maven-jar-plugin manifest mainClass").isTrue();
    Class<?> main = Class.forName(m.group(1).trim());
    assertThat(main).isEqualTo(CdcSyncMain.class);
    assertThat(Modifier.isStatic(main.getMethod("main", String[].class).getModifiers())).isTrue();
    assertThat(pom)
        .contains("<addClasspath>true</addClasspath>")
        .contains("<classpathPrefix>lib/</classpathPrefix>")
        .contains("<finalName>hrms-tool-cdc-sync</finalName>")
        .contains("<goal>copy-dependencies</goal>");
  }
}
