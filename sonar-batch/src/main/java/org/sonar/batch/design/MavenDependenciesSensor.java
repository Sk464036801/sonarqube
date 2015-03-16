/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.design;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.*;
import org.sonar.api.config.Settings;
import org.sonar.api.design.Dependency;
import org.sonar.api.resources.Library;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.batch.index.ResourcePersister;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SupportedEnvironment("maven")
@RequiresDB
public class MavenDependenciesSensor implements Sensor {

  private static final String SONAR_MAVEN_PROJECT_DEPENDENCY = "sonar.maven.projectDependencies";

  private static final Logger LOG = LoggerFactory.getLogger(MavenDependenciesSensor.class);

  private final SonarIndex index;
  private final Settings settings;
  private final ResourcePersister resourcePersister;

  public MavenDependenciesSensor(Settings settings, SonarIndex index, ResourcePersister resourcePersister) {
    this.settings = settings;
    this.index = index;
    this.resourcePersister = resourcePersister;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return true;
  }

  private static class InputDependency {

    private final String key;

    private final String version;

    private String scope;

    List<InputDependency> dependencies = new ArrayList<InputDependency>();

    public InputDependency(String key, String version) {
      this.key = key;
      this.version = version;
    }

    public String key() {
      return key;
    }

    public String version() {
      return version;
    }

    public String scope() {
      return scope;
    }

    public InputDependency setScope(String scope) {
      this.scope = scope;
      return this;
    }

    public List<InputDependency> dependencies() {
      return dependencies;
    }
  }

  private static class DependencyDeserializer implements JsonDeserializer<InputDependency> {

    @Override
    public InputDependency deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {

      JsonObject dep = json.getAsJsonObject();
      String key = dep.get("k").getAsString();
      String version = dep.get("v").getAsString();
      InputDependency result = new InputDependency(key, version);
      result.setScope(dep.get("s").getAsString());
      JsonElement subDeps = dep.get("d");
      if (subDeps != null) {
        JsonArray arrayOfSubDeps = subDeps.getAsJsonArray();
        for (JsonElement e : arrayOfSubDeps) {
          result.dependencies().add(deserialize(e, typeOfT, context));
        }
      }
      return result;
    }

  }

  @Override
  public void analyse(final Project project, final SensorContext context) {
    if (settings.hasKey(SONAR_MAVEN_PROJECT_DEPENDENCY)) {
      LOG.debug("Using dependency provided by property " + SONAR_MAVEN_PROJECT_DEPENDENCY);
      String depsAsJson = settings.getString(SONAR_MAVEN_PROJECT_DEPENDENCY);
      Collection<InputDependency> deps;
      try {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(InputDependency.class, new DependencyDeserializer());
        Gson gson = gsonBuilder.create();

        Type collectionType = new TypeToken<Collection<InputDependency>>() {
        }.getType();
        deps = gson.fromJson(depsAsJson, collectionType);
        saveDependencies(project, project, deps, context);
      } catch (Exception e) {
        throw new IllegalStateException("Unable to deserialize dependency information: " + depsAsJson, e);
      }
    }
  }

  private void saveDependencies(Project project, Resource from, Collection<InputDependency> deps, SensorContext context) {
    for (InputDependency inputDep : deps) {
      Resource to = toResource(project, inputDep, context);
      Dependency dependency = new Dependency(from, to);
      dependency.setUsage(inputDep.scope());
      dependency.setWeight(1);
      context.saveDependency(dependency);
      if (!inputDep.dependencies().isEmpty()) {
        saveDependencies(project, to, inputDep.dependencies(), context);
      }
    }
  }

  private Resource toResource(Project project, InputDependency dependency, SensorContext context) {
    Project depProject = new Project(dependency.key(), project.getBranch(), dependency.key());
    Resource result = context.getResource(depProject);
    if (result == null || !((Project) result).getAnalysisVersion().equals(dependency.version())) {
      Library lib = new Library(dependency.key(), dependency.version());
      index.addResource(lib);
      // Temporary hack since we need snapshot id to persist dependencies
      resourcePersister.persist();
      result = context.getResource(lib);
    }
    return result;
  }

  @Override
  public String toString() {
    return "Maven dependencies";
  }
}
