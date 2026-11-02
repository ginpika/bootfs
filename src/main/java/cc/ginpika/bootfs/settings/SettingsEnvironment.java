package cc.ginpika.bootfs.settings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.nio.file.Path;
import java.util.*;

/** Runs after profile/config-data resolution and before Tomcat/ServerProperties binding. */
public final class SettingsEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER+1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Path root=Path.of(env.getProperty("tfs.root-path",env.getProperty("TFS_ROOT_PATH","./bootfs")));
        try (SettingsDatabase db=new SettingsDatabase(root)) {
            var state=SettingsTransition.apply(root,db.initialize(env,root),db);
            var values=new SettingsValues(state.getJSONObject("values"));
            Map<String,Object> properties=new LinkedHashMap<>();
            SettingsValues.FIELDS.forEach((key,field) -> properties.put(field.property(),values.map().get(key)));
            properties.put("bootfs.settings.loaded-root",root.toAbsolutePath().normalize().toString());
            env.getPropertySources().addFirst(new MapPropertySource("nodeDatabaseSettings",properties));
        } catch (Exception failure) { throw new IllegalStateException("Cannot initialize node settings",failure); }
    }
}
