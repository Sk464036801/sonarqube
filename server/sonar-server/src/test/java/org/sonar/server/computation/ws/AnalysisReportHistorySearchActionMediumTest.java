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

package org.sonar.server.computation.ws;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.security.DefaultGroups;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.computation.db.AnalysisReportDto;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.permission.PermissionFacade;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.user.UserDto;
import org.sonar.server.computation.AnalysisReportQueue;
import org.sonar.server.db.DbClient;
import org.sonar.server.tester.ServerTester;
import org.sonar.server.user.MockUserSession;
import org.sonar.server.ws.WsTester;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class AnalysisReportHistorySearchActionMediumTest {
  private static final String DEFAULT_PROJECT_KEY = "DefaultProjectKey";
  @ClassRule
  public static ServerTester tester = new ServerTester();

  private DbClient dbClient;
  private DbSession session;
  private WsTester wsTester;
  private AnalysisReportQueue queue;
  private MockUserSession userSession;

  @Before
  public void before() {
    tester.clearDbAndIndexes();
    dbClient = tester.get(DbClient.class);
    wsTester = tester.get(WsTester.class);
    session = dbClient.openSession(false);
    queue = tester.get(AnalysisReportQueue.class);

    UserDto connectedUser = new UserDto().setLogin("gandalf").setName("Gandalf");
    dbClient.userDao().insert(session, connectedUser);

    userSession = MockUserSession.set()
      .setLogin(connectedUser.getLogin())
      .setUserId(connectedUser.getId().intValue())
      .setGlobalPermissions(GlobalPermissions.SCAN_EXECUTION);
  }

  @After
  public void after() {
    MyBatis.closeQuietly(session);
  }

  @Test
  public void add_and_try_to_retrieve_activities() throws Exception {
    insertPermissionsForProject(DEFAULT_PROJECT_KEY);
    queue.add(DEFAULT_PROJECT_KEY);
    queue.add(DEFAULT_PROJECT_KEY);
    queue.add(DEFAULT_PROJECT_KEY);

    List<AnalysisReportDto> reports = queue.all();
    for (AnalysisReportDto report : reports) {
      report.succeed();
      queue.remove(report);
    }

    session.commit();

    WsTester.TestRequest request = wsTester.newGetRequest(AnalysisReportWebService.API_ENDPOINT, AnalysisReportHistorySearchAction.SEARCH_ACTION);
    WsTester.Result result = request.execute();

    assertThat(result).isNotNull();
    // TODO find a way to mock System2 to control date and then assert the Json
    System.out.println(result.outputAsString());
  }

  private ComponentDto insertPermissionsForProject(String projectKey) {
    ComponentDto project = new ComponentDto().setKey(projectKey);
    dbClient.componentDao().insert(session, project);

    tester.get(PermissionFacade.class).insertGroupPermission(project.getId(), DefaultGroups.ANYONE, UserRole.USER, session);
    userSession.addProjectPermissions(UserRole.USER, project.key());

    session.commit();

    return project;
  }
}