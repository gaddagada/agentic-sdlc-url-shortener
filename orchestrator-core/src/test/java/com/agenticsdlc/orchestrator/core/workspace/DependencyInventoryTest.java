package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.DependencyFlag;
import com.agenticsdlc.orchestrator.core.workspace.DependencyInventory.FlagKind;

class DependencyInventoryTest {

    static final String LEGACY_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.agenticsdlc</groupId>
                <artifactId>agentic-sdlc-parent</artifactId>
                <version>1.0.0-SNAPSHOT</version>
              </parent>
              <artifactId>legacy</artifactId>
              <properties><lib.version>2.0-SNAPSHOT</lib.version></properties>
              <dependencies>
                <dependency><groupId>com.acme</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                <dependency>
                  <groupId>com.acme</groupId><artifactId>native</artifactId><version>1.0</version>
                  <scope>system</scope><systemPath>${basedir}/lib/native.jar</systemPath>
                </dependency>
                <dependency><groupId>com.acme</groupId><artifactId>edge</artifactId><version>LATEST</version></dependency>
                <dependency><groupId>com.acme</groupId><artifactId>ranged</artifactId><version>[1.0,2.0)</version></dependency>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
                <dependency>
                  <groupId>com.agenticsdlc</groupId><artifactId>orchestrator-core</artifactId><version>${project.version}</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
                  <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId></plugin>
                </plugins>
              </build>
            </project>
            """;

    static final String TOOLS_POM = """
            <project>
              <groupId>com.acme</groupId>
              <artifactId>tools</artifactId>
              <version>1.0</version>
              <build>
                <plugins><plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins>
                <pluginManagement><plugins>
                  <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                </plugins></pluginManagement>
              </build>
              <profiles><profile><id>ci</id>
                <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><artifactId>maven-jar-plugin</artifactId></plugin></plugins></build>
              </profile></profiles>
            </project>
            """;

    static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Copies this repository's POMs (root and modules) into a directory; skips the test when they are not found. */
    static void copyRepositoryPoms(Path target) throws IOException {
        Path repo = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(repo.resolve("orchestrator-core/pom.xml"))) {
            repo = repo.getParent();
        }
        Assumptions.assumeTrue(repo != null && Files.isRegularFile(repo.resolve("pom.xml"))
                && Files.isRegularFile(repo.resolve("orchestrator-core/pom.xml")), "repository POMs not found");
        for (String pom : List.of("pom.xml", "orchestrator-core/pom.xml", "orchestrator-app/pom.xml",
                "url-shortener/pom.xml")) {
            if (Files.isRegularFile(repo.resolve(pom))) {
                write(target, pom, Files.readString(repo.resolve(pom)));
            }
        }
    }

    private static DependencyRecord find(DependencyInventory inventory, String module, String artifactId) {
        return inventory.dependencies().stream().filter(d -> d.module().equals(module)
                && d.artifactId().equals(artifactId)).findFirst().orElseThrow();
    }

    @Test
    void buildOutputIdeMetadataAndRuntimeDataAreNeverSearchedForPoms(@TempDir Path root) throws IOException {
        write(root, "tools/pom.xml", TOOLS_POM);
        for (String skipped : List.of("data", ".vscode", "build", "out", ".idea", "target", "node_modules",
                "sandbox")) {
            write(root, skipped + "/nested/pom.xml", TOOLS_POM.replace("tools", "ignored-" + skipped));
        }

        DependencyInventory inventory = DependencyInventory.read(root);

        assertThat(inventory.modules()).extracting(DependencyInventory.PomModule::path)
                .containsExactly("tools/pom.xml");
        assertThat(DependencyInventory.SKIPPED_DIRECTORIES).contains("data", ".idea", ".vscode", "build", "out");
    }

    @Test
    void versionPropertiesResolveThroughThePropertiesChainAndUnresolvableOnesAreUnknown(@TempDir Path root)
            throws IOException {
        write(root, "pom.xml", """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                    <version>4.1.2</version>
                  </parent>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><base.version>3.2</base.version><lib.version>${base.version}.1</lib.version></properties>
                </project>
                """);
        write(root, "app/pom.xml", """
                <project>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                    <dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-databind</artifactId>
                      <version>${jackson.version}</version></dependency>
                    <dependency><groupId>com.acme</groupId><artifactId>ranged</artifactId>
                      <version>[${base.version},${missing.max})</version></dependency>
                  </dependencies>
                </project>
                """);

        DependencyInventory inventory = DependencyInventory.read(root);

        assertThat(find(inventory, "app/pom.xml", "lib").version()).as("nested property of the parent").isEqualTo(
                "3.2.1");
        assertThat(find(inventory, "app/pom.xml", "jackson-databind").version())
                .as("defined by the external Spring Boot parent").isEqualTo(DependencyRecord.UNKNOWN);
        assertThat(find(inventory, "app/pom.xml", "ranged").version()).isEqualTo(DependencyRecord.UNKNOWN);
        assertThat(inventory.dependencies()).noneMatch(d -> d.version().contains("${"));
        assertThat(inventory.flags()).noneMatch(f -> f.coordinate().contains("${"))
                .filteredOn(f -> f.kind() == FlagKind.VERSION_RANGE).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("[3.2,${missing.max})"));
    }

    @Test
    void inventoriesAMultiModuleTreeAndFlagsHygieneProblems(@TempDir Path root) throws IOException {
        copyRepositoryPoms(root);
        write(root, "legacy/pom.xml", LEGACY_POM);
        write(root, "tools/pom.xml", TOOLS_POM);
        write(root, "legacy/target/classes/pom.xml", LEGACY_POM.replace("legacy", "ignored-target"));
        write(root, "node_modules/x/pom.xml", TOOLS_POM.replace("tools", "ignored-node"));
        write(root, ".git/pom.xml", TOOLS_POM.replace("tools", "ignored-git"));

        DependencyInventory inventory = DependencyInventory.read(root);

        assertThat(inventory.modules()).extracting(DependencyInventory.PomModule::path).containsExactly(
                "legacy/pom.xml", "orchestrator-app/pom.xml", "orchestrator-core/pom.xml", "pom.xml",
                "tools/pom.xml", "url-shortener/pom.xml");
        assertThat(inventory.modules()).filteredOn(m -> m.path().equals("legacy/pom.xml")).singleElement()
                .isEqualTo(new DependencyInventory.PomModule("legacy/pom.xml", "com.agenticsdlc", "legacy",
                        "1.0.0-SNAPSHOT", "com.agenticsdlc:agentic-sdlc-parent:1.0.0-SNAPSHOT"));
        assertThat(inventory.modules()).filteredOn(m -> m.path().equals("pom.xml")).singleElement()
                .satisfies(m -> assertThat(m.parent()).startsWith("org.springframework.boot:spring-boot-starter-parent:"));
        assertThat(inventory.notes()).isEmpty();

        assertThat(find(inventory, "legacy/pom.xml", "lib").version()).as("property resolved").isEqualTo(
                "2.0-SNAPSHOT");
        assertThat(find(inventory, "legacy/pom.xml", "orchestrator-core").version()).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(find(inventory, "legacy/pom.xml", "slf4j-api").version()).isEqualTo(DependencyRecord.MANAGED);
        assertThat(find(inventory, "legacy/pom.xml", "native").scope()).isEqualTo("system");
        assertThat(find(inventory, "legacy/pom.xml", "maven-surefire-plugin")).satisfies(p -> {
            assertThat(p.groupId()).isEqualTo("org.apache.maven.plugins");
            assertThat(p.version()).as("the external Spring Boot parent may manage it").isEqualTo(
                    DependencyRecord.UNKNOWN);
            assertThat(p.kind()).isEqualTo(DependencyRecord.Kind.PLUGIN);
            assertThat(p.scope()).isEqualTo("build");
            assertThat(p.section()).isEqualTo("build/plugins");
        });
        assertThat(find(inventory, "legacy/pom.xml", "jacoco-maven-plugin").version()).as("managed by the root POM")
                .isEqualTo(DependencyRecord.MANAGED);
        assertThat(find(inventory, "pom.xml", "jacoco-maven-plugin").version()).isEqualTo(DependencyRecord.MANAGED);
        assertThat(inventory.dependencies()).filteredOn(d -> d.module().equals("pom.xml")
                && d.section().equals("dependencyManagement")).extracting(DependencyRecord::artifactId)
                .contains("orchestrator-core");
        assertThat(find(inventory, "orchestrator-app/pom.xml", "orchestrator-core").version())
                .isEqualTo(DependencyRecord.MANAGED);
        assertThat(inventory.dependencies()).filteredOn(d -> d.module().equals("tools/pom.xml"))
                .extracting(DependencyRecord::artifactId, DependencyRecord::version, DependencyRecord::section)
                .containsExactly(tuple("maven-compiler-plugin", DependencyRecord.NONE, "build/plugins"),
                        tuple("maven-jar-plugin", "3.4.2", "build/pluginManagement"),
                        tuple("junit", "4.13.2", "profile:ci/dependencies"),
                        tuple("maven-jar-plugin", DependencyRecord.MANAGED, "profile:ci/build/plugins"));
        assertThat(inventory.dependencies()).noneMatch(d -> d.module().contains("target")
                || d.module().contains("node_modules") || d.module().contains(".git"));

        assertThat(inventory.flags()).extracting(DependencyFlag::module, DependencyFlag::kind, DependencyFlag::severity)
                .containsExactly(
                        tuple("legacy/pom.xml", FlagKind.SNAPSHOT_VERSION, Severity.MEDIUM),
                        tuple("legacy/pom.xml", FlagKind.SYSTEM_SCOPE, Severity.HIGH),
                        tuple("legacy/pom.xml", FlagKind.DYNAMIC_VERSION, Severity.HIGH),
                        tuple("legacy/pom.xml", FlagKind.VERSION_RANGE, Severity.MEDIUM),
                        tuple("legacy/pom.xml", FlagKind.DUPLICATE_DECLARATION, Severity.LOW),
                        tuple("tools/pom.xml", FlagKind.UNVERSIONED_PLUGIN, Severity.LOW));
        assertThat(inventory.flags().getFirst().coordinate()).isEqualTo("com.acme:lib:2.0-SNAPSHOT");
        assertThat(inventory.flags().getFirst().message()).contains("not built in this workspace");
        assertThat(inventory.flagsAtLeast(Severity.HIGH)).hasSize(2);
    }

    @Test
    void theSecureParserRejectsDoctypesAndNeverResolvesEntities(@TempDir Path root) throws IOException {
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        write(root, "ws/pom.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "%s">]>
                <project><groupId>g</groupId><artifactId>&xxe;</artifactId><version>1</version></project>
                """.formatted(secret.toUri()));
        write(root, "ws/billion/pom.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
                <project><artifactId>&lol2;</artifactId></project>
                """);
        write(root, "ws/xinclude/pom.xml", """
                <project xmlns:xi="http://www.w3.org/2001/XInclude"><groupId>g</groupId><artifactId>inc</artifactId>
                  <version>1</version><xi:include href="%s" parse="text"/></project>
                """.formatted(secret.toUri()));

        DependencyInventory inventory = DependencyInventory.read(root.resolve("ws"));

        assertThat(inventory.flags()).filteredOn(f -> f.kind() == FlagKind.UNPARSEABLE_POM)
                .extracting(DependencyFlag::module).containsExactly("billion/pom.xml", "pom.xml");
        assertThat(inventory.flags()).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.message()).contains("DOCTYPE").doesNotContain("TOP-SECRET-VALUE");
        });
        assertThat(inventory.modules()).extracting(DependencyInventory.PomModule::artifactId).containsExactly("inc");
        assertThat(inventory.toString()).doesNotContain("TOP-SECRET-VALUE");
    }

    @Test
    void aMissingRootYieldsAnEmptyInventoryWithANote(@TempDir Path root) {
        DependencyInventory inventory = DependencyInventory.read(root.resolve("missing"));
        assertThat(inventory.modules()).isEmpty();
        assertThat(inventory.notes()).singleElement().asString().contains("not a readable directory");
        assertThat(DependencyInventory.empty().dependencies()).isEmpty();
        assertThat(DependencyInventory.read(root).modules()).as("no POMs").isEmpty();
    }
}
