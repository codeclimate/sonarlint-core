/*
 * SonarLint Core - ITs - Tests
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package its;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.locator.FileLocation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.wsclient.permissions.PermissionParameters;
import org.sonar.wsclient.services.PropertyCreateQuery;
import org.sonar.wsclient.services.PropertyDeleteQuery;
import org.sonar.wsclient.user.UserParameters;
import org.sonarqube.ws.QualityProfiles.SearchWsResponse;
import org.sonarqube.ws.QualityProfiles.SearchWsResponse.QualityProfile;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.PostRequest;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.WsRequest;
import org.sonarqube.ws.client.WsResponse;
import org.sonarqube.ws.client.permission.RemoveGroupWsRequest;
import org.sonarqube.ws.client.qualityprofile.SearchWsRequest;
import org.sonarqube.ws.client.setting.SetRequest;
import org.sonarsource.sonarlint.core.ConnectedSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.WsHelperImpl;
import org.sonarsource.sonarlint.core.client.api.common.analysis.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedGlobalConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine.State;
import org.sonarsource.sonarlint.core.client.api.connected.ServerConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.StorageUpdateCheckResult;
import org.sonarsource.sonarlint.core.client.api.connected.WsHelper;
import org.sonarsource.sonarlint.core.client.api.exceptions.UnsupportedServerException;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class ConnectedModeTest extends AbstractConnectedTest {

  private static final String PROJECT_KEY_JAVA = "sample-java";
  private static final String PROJECT_KEY_JAVA_CUSTOM_SENSOR = "sample-java-custom-sensor";
  private static final String PROJECT_KEY_JAVA_PACKAGE = "sample-java-package";
  private static final String PROJECT_KEY_JAVA_EMPTY = "sample-java-empty";
  private static final String PROJECT_KEY_PHP = "sample-php";
  private static final String PROJECT_KEY_JAVASCRIPT = "sample-javascript";
  private static final String PROJECT_KEY_JAVASCRIPT_CUSTOM = "sample-javascript-custom";
  private static final String PROJECT_KEY_PYTHON = "sample-python";

  @ClassRule
  public static Orchestrator ORCHESTRATOR = Orchestrator.builderEnv()
    .addPlugin("java")
    .addPlugin("javascript")
    .addPlugin("php")
    .addPlugin("python")
    .addPlugin(FileLocation.of("../plugins/javascript-custom-rules/target/javascript-custom-rules-plugin.jar"))
    .addPlugin(FileLocation.of("../plugins/custom-sensor-plugin/target/custom-sensor-plugin.jar"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/java-sonarlint.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/java-sonarlint-package.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/java-empty-sonarlint.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/javascript-sonarlint.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/javascript-custom.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/php-sonarlint.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/python-sonarlint.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/custom-sensor.xml"))
    .build();

  @ClassRule
  public static TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  private static WsClient adminWsClient;
  private static Path sonarUserHome;

  private ConnectedSonarLintEngine engine;

  @BeforeClass
  public static void prepare() throws Exception {
    adminWsClient = newAdminWsClient(ORCHESTRATOR);
    if (ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("6.3")) {
      adminWsClient.settings().set(SetRequest.builder().setKey("sonar.forceAuthentication").setValue("true").build());
    } else {
      ORCHESTRATOR.getServer().getAdminWsClient().create(new PropertyCreateQuery("sonar.forceAuthentication", "true"));
    }
    sonarUserHome = temp.newFolder().toPath();

    removeGroupPermission("anyone", "scan");

    ORCHESTRATOR.getServer().adminWsClient().userClient()
      .create(UserParameters.create()
        .login(SONARLINT_USER)
        .password(SONARLINT_PWD)
        .passwordConfirmation(SONARLINT_PWD)
        .name("SonarLint"));

    // addUserPermission("sonarlint", "dryRunScan");

    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVA, "Sample Java");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVA_PACKAGE, "Sample Java Package");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVA_EMPTY, "Sample Java Empty");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_PHP, "Sample PHP");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVASCRIPT, "Sample Javascript");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVASCRIPT_CUSTOM, "Sample Javascript Custom");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_PYTHON, "Sample Python");
    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVA_CUSTOM_SENSOR, "Sample Java Custom");

    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVA, "java", "SonarLint IT Java");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVA_PACKAGE, "java", "SonarLint IT Java Package");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVA_EMPTY, "java", "SonarLint IT Java Empty");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_PHP, "php", "SonarLint IT PHP");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVASCRIPT, "js", "SonarLint IT Javascript");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVASCRIPT_CUSTOM, "js", "SonarLint IT Javascript Custom");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_PYTHON, "py", "SonarLint IT Python");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVA_CUSTOM_SENSOR, "java", "SonarLint IT Custom Sensor");

    // Build project to have bytecode
    ORCHESTRATOR.executeBuild(MavenBuild.create(new File("projects/sample-java/pom.xml")).setGoals("clean package"));
  }

  @Before
  public void start() {
    FileUtils.deleteQuietly(sonarUserHome.toFile());
    engine = new ConnectedSonarLintEngineImpl(ConnectedGlobalConfiguration.builder()
      .setServerId("orchestrator")
      .setSonarLintUserHome(sonarUserHome)
      .setLogOutput((msg, level) -> System.out.println(msg))
      .build());
    assertThat(engine.getGlobalStorageStatus()).isNull();
    assertThat(engine.getState()).isEqualTo(State.NEVER_UPDATED);

    // This profile is altered in a test
    ORCHESTRATOR.getServer().restoreProfile(FileLocation.ofClasspath("/java-sonarlint.xml"));
  }

  @After
  public void stop() {
    ORCHESTRATOR.getServer().getAdminWsClient().delete(new PropertyDeleteQuery("sonar.java.file.suffixes"));
    ORCHESTRATOR.getServer().getAdminWsClient().delete(new PropertyDeleteQuery("sonar.java.file.suffixes", PROJECT_KEY_JAVA));
    try {
      engine.stop(true);
    } catch (Exception e) {
      // Ignore
    }
  }

  @Test
  public void downloadModules() throws Exception {
    updateGlobal();
    assertThat(engine.allModulesByKey()).hasSize(8);
    ORCHESTRATOR.getServer().provisionProject("foo-bar", "Foo");
    assertThat(engine.downloadAllModules(getServerConfig(), null)).hasSize(9).containsKeys("foo-bar", PROJECT_KEY_JAVA, PROJECT_KEY_PHP);
    assertThat(engine.allModulesByKey()).hasSize(9).containsKeys("foo-bar", PROJECT_KEY_JAVA, PROJECT_KEY_PHP);
  }

  @Test
  public void updateNoAuth() throws Exception {
    try {
      engine.update(ServerConfiguration.builder()
        .url(ORCHESTRATOR.getServer().getUrl())
        .userAgent("SonarLint ITs")
        .build(), null);
      fail("Exception expected");
    } catch (Exception e) {
      assertThat(e).hasMessage("Not authorized. Please check server credentials.");
    }
  }

  @Test
  public void parsingErrorJava() throws IOException {
    String fileContent = "pac kage its; public class MyTest { }";
    Path testFile = temp.newFile("MyTestParseError.java").toPath();
    Files.write(testFile, fileContent.getBytes(StandardCharsets.UTF_8));

    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    SaveIssueListener issueListener = new SaveIssueListener();
    AnalysisResults results = engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, testFile.toString()), issueListener, null, null);

    assertThat(results.failedAnalysisFiles()).hasSize(1);
  }

  @Test
  public void parsingErrorJavascript() throws IOException {
    String fileContent = "asd asd";
    Path testFile = temp.newFile("MyTest.js").toPath();
    Files.write(testFile, fileContent.getBytes(StandardCharsets.UTF_8));

    updateGlobal();
    updateModule(PROJECT_KEY_JAVASCRIPT);

    SaveIssueListener issueListener = new SaveIssueListener();
    AnalysisResults results = engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVASCRIPT, testFile.toString()), issueListener, null, null);

    assertThat(results.failedAnalysisFiles()).hasSize(1);
  }

  @Test
  public void semanticErrorJava() throws IOException {
    String fileContent = "package its;public class MyTest {int a;int a;}";
    Path testFile = temp.newFile("MyTestSemanticError.java").toPath();
    Files.write(testFile, fileContent.getBytes(StandardCharsets.UTF_8));

    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    SaveIssueListener issueListener = new SaveIssueListener();
    AnalysisResults results = engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, testFile.toString()), issueListener, null, null);

    assertThat(results.failedAnalysisFiles()).hasSize(1);
  }

  @Test
  public void globalUpdate() throws Exception {
    updateGlobal();

    assertThat(engine.getState()).isEqualTo(State.UPDATED);
    assertThat(engine.getGlobalStorageStatus()).isNotNull();
    assertThat(engine.getGlobalStorageStatus().isStale()).isFalse();
    assertThat(engine.getGlobalStorageStatus().getServerVersion()).startsWith(StringUtils.substringBefore(ORCHESTRATOR.getServer().version().toString(), "-"));

    if (supportHtmlDesc()) {
      assertThat(engine.getRuleDetails("squid:S106").getHtmlDescription()).contains("When logging a message there are");
    } else {
      assertThat(engine.getRuleDetails("squid:S106").getHtmlDescription()).contains("Rule descriptions are only available in SonarLint with SonarQube 5.1+");
    }

    assertThat(engine.getModuleStorageStatus(PROJECT_KEY_JAVA)).isNull();
  }

  @Test
  public void updateProject() throws Exception {
    updateGlobal();

    updateModule(PROJECT_KEY_JAVA);

    assertThat(engine.getModuleStorageStatus(PROJECT_KEY_JAVA)).isNotNull();
  }

  @Test
  public void verifyExtendedDescription() throws Exception {
    assumeTrue(supportHtmlDesc());
    updateGlobal();

    String ruleKey = "squid:S106";

    assertThat(engine.getRuleDetails(ruleKey).getExtendedDescription()).isEmpty();

    String extendedDescription = "my dummy extended description";

    WsRequest request = new PostRequest("/api/rules/update")
      .setParam("key", ruleKey)
      .setParam("markdown_note", extendedDescription);
    WsResponse response = adminWsClient.wsConnector().call(request);
    assertThat(response.code()).isEqualTo(200);

    updateGlobal();

    assertThat(engine.getRuleDetails(ruleKey).getExtendedDescription()).isEqualTo(extendedDescription);
  }

  @Test
  public void analysisJavascript() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVASCRIPT);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVASCRIPT, PROJECT_KEY_JAVASCRIPT, "src/Person.js"), issueListener, null, null);
    assertThat(issueListener.getIssues()).hasSize(1);
  }

  @Test
  public void analysisJavascriptWithCustomRules() throws Exception {

    updateGlobal();
    updateModule(PROJECT_KEY_JAVASCRIPT_CUSTOM);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVASCRIPT_CUSTOM, PROJECT_KEY_JAVASCRIPT_CUSTOM, "src/Person.js"), issueListener, null, null);
    assertThat(issueListener.getIssues()).extracting("ruleKey", "startLine").containsOnly(
      tuple("custom:S1", 3),
      tuple("custom:S1", 7));
  }

  @Test
  public void analysisPHP() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_PHP);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_PHP, PROJECT_KEY_PHP, "src/Math.php"), issueListener, null, null);
    assertThat(issueListener.getIssues()).hasSize(1);
  }

  @Test
  public void analysisPython() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_PYTHON);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_PYTHON, PROJECT_KEY_PYTHON, "src/hello.py"), issueListener, null, null);
    assertThat(issueListener.getIssues()).hasSize(1);
  }

  @Test
  public void analysisUseQualityProfile() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);

    assertThat(issueListener.getIssues()).hasSize(2);
  }

  @Test
  public void analysisIssueOnDirectory() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA_PACKAGE);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA_PACKAGE, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);

    assertThat(issueListener.getIssues()).extracting("ruleKey", "inputFile.path").containsOnly(
      tuple("squid:S106", Paths.get("projects/sample-java/src/main/java/foo/Foo.java").toAbsolutePath().toString()),
      tuple("squid:S1228", null));
  }

  @Test
  public void customSensorsNotExecuted() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA_CUSTOM_SENSOR);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA_CUSTOM_SENSOR, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);

    assertThat(issueListener.getIssues()).isEmpty();
  }

  @Test
  public void analysisJavaPomXml() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA, "pom.xml"), issueListener, null, null);

    assertThat(issueListener.getIssues()).hasSize(1);
  }

  @Test
  public void analysisTemplateRule() throws Exception {
    // WS quality profile is not available before 5.2 so let's skip this test
    assumeTrue(ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("5.2"));
    SearchWsRequest searchReq = new SearchWsRequest();
    searchReq.setQualityProfile("SonarLint IT Java");
    searchReq.setProjectKey(PROJECT_KEY_JAVA);
    searchReq.setDefaults(false);
    SearchWsResponse search = adminWsClient.qualityProfiles().search(searchReq);
    QualityProfile qp = null;
    for (QualityProfile q : search.getProfilesList()) {
      if (q.getName().equals("SonarLint IT Java")) {
        qp = q;
      }
    }
    assertThat(qp).isNotNull();

    WsRequest request = new PostRequest("/api/rules/create")
      .setParam("custom_key", "myrule")
      .setParam("name", "myrule")
      .setParam("markdown_description", "my_rule_description")
      .setParam("params", "methodName=echo;className=foo.Foo;argumentTypes=int")
      .setParam("template_key", "squid:S2253")
      .setParam("severity", "MAJOR");
    WsResponse response = adminWsClient.wsConnector().call(request);
    assertTrue(response.isSuccessful());

    request = new PostRequest("/api/qualityprofiles/activate_rule")
      .setParam("profile_key", qp.getKey())
      .setParam("rule_key", "squid:myrule");
    response = adminWsClient.wsConnector().call(request);
    assertTrue(response.isSuccessful());

    try {

      updateGlobal();
      updateModule(PROJECT_KEY_JAVA);

      SaveIssueListener issueListener = new SaveIssueListener();
      engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA,
        "src/main/java/foo/Foo.java",
        "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
        issueListener, null, null);

      assertThat(issueListener.getIssues()).hasSize(3);

      assertThat(engine.getRuleDetails("squid:myrule").getHtmlDescription()).contains("my_rule_description");

    } finally {

      request = new PostRequest("/api/rules/delete")
        .setParam("key", "squid:myrule");
      response = adminWsClient.wsConnector().call(request);
      assertTrue(response.isSuccessful());
    }
  }

  @Test
  public void analysisUseEmptyQualityProfile() throws Exception {
    assumeTrue(ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("5.2"));

    updateGlobal();
    updateModule(PROJECT_KEY_JAVA_EMPTY);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA_EMPTY, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);

    assertThat(issueListener.getIssues()).isEmpty();
  }

  @Test
  public void analysisUseConfiguration() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    SaveIssueListener issueListener = new SaveIssueListener();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);
    assertThat(issueListener.getIssues()).hasSize(2);

    // Override default file suffixes in global props so that input file is not considered as a Java file
    setSettings(null, "sonar.java.file.suffixes", ".foo");
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    issueListener.clear();
    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);

    // Override default file suffixes in project props so that input file is considered as a Java file again
    setSettings(PROJECT_KEY_JAVA, "sonar.java.file.suffixes", ".java");
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    engine.analyze(createAnalysisConfiguration(PROJECT_KEY_JAVA, PROJECT_KEY_JAVA,
      "src/main/java/foo/Foo.java",
      "sonar.java.binaries", new File("projects/sample-java/target/classes").getAbsolutePath()),
      issueListener, null, null);
    assertThat(issueListener.getIssues()).hasSize(2);

  }

  @Test
  public void generateToken() {
    WsHelper ws = new WsHelperImpl();
    ServerConfiguration serverConfig = getServerConfig();

    if (!ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("5.4")) {
      exception.expect(UnsupportedServerException.class);
    }

    String token = ws.generateAuthenticationToken(serverConfig, "name", false);
    assertThat(token).isNotNull();

    token = ws.generateAuthenticationToken(serverConfig, "name", true);
    assertThat(token).isNotNull();
  }

  @Test
  public void checkForUpdate() throws Exception {
    updateGlobal();
    updateModule(PROJECT_KEY_JAVA);

    ServerConfiguration serverConfig = getServerConfig();

    StorageUpdateCheckResult result = engine.checkIfGlobalStorageNeedUpdate(serverConfig, null);
    assertThat(result.needUpdate()).isFalse();

    // restarting server should not lead to notify an update
    ORCHESTRATOR.restartServer();
    result = engine.checkIfGlobalStorageNeedUpdate(serverConfig, null);
    assertThat(result.needUpdate()).isFalse();

    // Change a global setting that is not in the whitelist
    setSettings(null, "sonar.foo", "bar");
    result = engine.checkIfGlobalStorageNeedUpdate(serverConfig, null);
    assertThat(result.needUpdate()).isFalse();

    // Change a global setting that *is* in the whitelist
    setMultiValuesSettings(null, "sonar.inclusions", "**/*");
    // Activate a new rule
    SearchWsResponse response = newAdminWsClient(ORCHESTRATOR).qualityProfiles().search(new SearchWsRequest().setLanguage("java"));
    String profileKey = response.getProfilesList().stream().filter(p -> p.getName().equals("SonarLint IT Java")).findFirst().get().getKey();
    ORCHESTRATOR.getServer().adminWsClient().post("api/qualityprofiles/activate_rule", "profile_key", profileKey, "rule_key", "squid:S1228");

    result = engine.checkIfGlobalStorageNeedUpdate(serverConfig, null);
    assertThat(result.needUpdate()).isTrue();
    assertThat(result.changelog()).containsOnly("Global settings updated", "Quality profile 'SonarLint IT Java' for language 'Java' updated");

    result = engine.checkIfModuleStorageNeedUpdate(serverConfig, PROJECT_KEY_JAVA, null);
    assertThat(result.needUpdate()).isFalse();

    // Change a project setting that is not in the whitelist
    setSettings(PROJECT_KEY_JAVA, "sonar.foo", "biz");
    result = engine.checkIfModuleStorageNeedUpdate(serverConfig, PROJECT_KEY_JAVA, null);
    assertThat(result.needUpdate()).isFalse();

    // Change a project setting that *is* in the whitelist
    setMultiValuesSettings(PROJECT_KEY_JAVA, "sonar.exclusions", "**/*.foo");

    result = engine.checkIfModuleStorageNeedUpdate(serverConfig, PROJECT_KEY_JAVA, null);
    assertThat(result.needUpdate()).isTrue();
    assertThat(result.changelog()).containsOnly("Project settings updated");
  }

  @Test
  public void downloadOrganizations() throws Exception {
    WsHelper helper = new WsHelperImpl();
    if (ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("6.3")) {
      assertThat(helper.listOrganizations(getServerConfig(), null)).hasSize(1);
    } else {
      try {
        helper.listOrganizations(getServerConfig(), null);
        fail("Expected exception");
      } catch (Exception e) {
        assertThat(e).isInstanceOf(UnsupportedServerException.class);
      }
    }
  }

  private void setSettings(@Nullable String moduleKey, String key, String value) {
    if (ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("6.3")) {
      adminWsClient.settings().set(SetRequest.builder()
        .setKey(key)
        .setValue(value)
        .setComponent(moduleKey)
        .build());
    } else {
      ORCHESTRATOR.getServer().getAdminWsClient()
        .create(new PropertyCreateQuery(key, value).setResourceKeyOrId(moduleKey));
    }
  }

  private void setMultiValuesSettings(@Nullable String moduleKey, String key, String... values) {
    if (ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("6.3")) {
      adminWsClient.settings().set(SetRequest.builder()
        .setKey(key)
        .setValues(Arrays.asList(values))
        .setComponent(moduleKey)
        .build());
    } else {
      ORCHESTRATOR.getServer().getAdminWsClient()
        .create(new PropertyCreateQuery(key, asList(values).stream().collect(Collectors.joining(","))).setResourceKeyOrId(moduleKey));
    }
  }

  private boolean supportHtmlDesc() {
    return ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("5.1");
  }

  private void updateModule(String projectKey) {
    engine.updateModule(getServerConfig(), projectKey, null);
  }

  private void updateGlobal() {
    engine.update(getServerConfig(), null);
  }

  private ServerConfiguration getServerConfig() {
    return ServerConfiguration.builder()
      .url(ORCHESTRATOR.getServer().getUrl())
      .userAgent("SonarLint ITs")
      .credentials(SONARLINT_USER, SONARLINT_PWD)
      .build();
  }

  private static void removeGroupPermission(String groupName, String permission) {
    if (ORCHESTRATOR.getServer().version().isGreaterThanOrEquals("5.2")) {
      adminWsClient.permissions().removeGroup(new RemoveGroupWsRequest()
        .setGroupName(groupName)
        .setPermission(permission));
    } else {
      ORCHESTRATOR.getServer().adminWsClient().permissionClient().removePermission(PermissionParameters.create().group(groupName).permission(permission));
    }
  }

  public static WsClient newAdminWsClient(Orchestrator orchestrator) {
    Server server = orchestrator.getServer();
    return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .url(server.getUrl())
      .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
      .build());
  }
}
