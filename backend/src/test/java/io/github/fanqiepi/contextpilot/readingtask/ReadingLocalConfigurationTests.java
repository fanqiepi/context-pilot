package io.github.fanqiepi.contextpilot.readingtask;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Config loading only: no application beans, database or provider calls. */
class ReadingLocalConfigurationTests {
    @TempDir Path directory;

    private StandardEnvironment load(String profile, String localPath, Boolean enabled) throws Exception {
        Path config=directory.resolve("application.yml");
        // Map relative imports to an isolated simulated working directory.
        String yaml=Files.readString(Path.of("src/main/resources/application.yml"));
        Files.writeString(config,yaml.replace("optional:file:./", "optional:"+directory.toUri()));
        if(localPath!=null) {
            Path local=directory.resolve(localPath);Files.createDirectories(local.getParent());
            Files.writeString(local,"contextpilot:\n  reading:\n    ai-enabled: "+enabled+"\nspring:\n  ai:\n    deepseek:\n      api-key: fixture-key\n");
        }
        var env=new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test",Map.of("spring.config.location",config.toUri().toString())));
        if(profile!=null)env.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(env);
        return env;
    }
    @Test void existingIdeEntryLoadsLocalSwitchFromRepositoryRoot() throws Exception {
        var env=load(null,"backend/config/application-local-secrets.yml",true);
        assertThat(env.getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isTrue();
        assertThat(env.getProperty("spring.ai.deepseek.api-key")).isEqualTo("fixture-key");
    }
    @Test void mavenWorkingDirectoryLoadsExplicitFalse() throws Exception {
        assertThat(load(null,"config/application-local-secrets.yml",false)
                .getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isFalse();
    }
    @Test void missingLocalFileKeepsAiDisabled() throws Exception {
        assertThat(load(null,null,null).getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isFalse();
    }
    @Test void explicitLocalProfileStillLoadsFromRepositoryRoot() throws Exception {
        assertThat(load("local-secrets","backend/config/application-local-secrets.yml",true)
                .getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isTrue();
    }
    @Test void offlineProfileDoesNotImportLocalCredentialsOrSwitch() throws Exception {
        var env=load("offline","backend/config/application-local-secrets.yml",true);
        assertThat(env.getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.ai.deepseek.api-key")).isEmpty();
    }
    @Test void ordinaryTestClasspathDefaultsToOffline() {
        var env=new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test",Map.of("spring.config.location","classpath:/")));
        ConfigDataEnvironmentPostProcessor.applyTo(env);
        assertThat(env.getDefaultProfiles()).containsExactly("offline");
        assertThat(env.getProperty("contextpilot.reading.ai-enabled",Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.ai.deepseek.api-key")).isEmpty();
    }
}
