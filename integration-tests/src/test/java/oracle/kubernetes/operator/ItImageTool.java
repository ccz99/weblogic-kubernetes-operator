// Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.ExecCommand;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Simple JUnit test file used for testing Operator.
 *
 * <p>This test is used for creating Operator(s) and domain(s) which are managed by the Operator(s).
 * WebLogic docker image is created by WebLogic Image Tool
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItImageTool extends BaseTest {
  private static final String TEST_RESOURCE_LOC = "integration-tests/src/test/resources";
  private static String TEST_APP_PROPS_FILE = "OperatorWIT.properties";
  private static String WLS_IMAGE_VERSION = "12.2.1.3.0";
  private static String WLS_IMAGE_NAME = "imagetool/build/weblogic";
  private static String WLS_IMAGE_TAG;

  private static Operator operator;
  private static Domain domain;

  /**
   * This method gets called only once before any of the test methods are executed. It creates
   * a WebLogic docker image using WebLogic Image Tool. It does the initialization of the integration
   * test properties defined in OperatorIT.properties and setting the resultRoot, pvRoot and projectRoot attributes.
   * It also creates Operator, domain and a test domain yaml file.
   *
   * @throws Exception exception
   */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    if (FULLTEST) {
      // Determine image name and version to be used
      if (System.getenv("IMAGE_NAME_WEBLOGIC") != null) {
        WLS_IMAGE_NAME = System.getenv("IMAGE_NAME_WEBLOGIC");
      }

      if (System.getenv("IMAGE_TAG_WEBLOGIC") != null) {
        WLS_IMAGE_VERSION = System.getenv("IMAGE_TAG_WEBLOGIC");
      }

      WLS_IMAGE_TAG = WLS_IMAGE_NAME + ":" + WLS_IMAGE_VERSION;
      logger.info("WebLogic image name is: " + WLS_IMAGE_TAG);

      // env vars IMAGE_NAME_WEBLOGIC and IMAGE_TAG_WEBLOGIC overwrite the wls docker image
      // specified in OperatorIT.properties
      if (System.getenv("IMAGE_NAME_WEBLOGIC") != null && System.getenv("IMAGE_TAG_WEBLOGIC") != null) {
        TEST_APP_PROPS_FILE = APP_PROPS_FILE;
      }
      logger.info("Using <" + TEST_APP_PROPS_FILE + "> to create Operator and Domain");

      // Build WebLogic docker image using imagetool
      buildWlsDockerImage();

      // initialize test properties and create the directories
      initialize(TEST_APP_PROPS_FILE);

      // Create operator1
      if (operator == null) {
        logger.info("Creating Operator & waiting for the script to complete execution");
        operator = TestUtils.createOperator(OPERATOR1_YAML);
      }

      // create domain
      if (domain == null) {
        logger.info("Creating WLS Domain & waiting for the script to complete execution");
        domain = TestUtils.createDomain(DOMAINONPV_WLST_YAML);
        domain.verifyDomainCreated();
      }
    }
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories and restore OperatorIT.properties.
   *
   * @throws Exception exception
   */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    if (FULLTEST) {
      logger.info("++++++++++++++++++++++++++++++++++");
      logger.info("BEGIN");
      logger.info("Run once, release cluster lease");

      tearDown(new Object() {}.getClass().getEnclosingClass().getSimpleName());

      logger.info("SUCCESS");
    }
  }

  /**
   * Verify that WebLogic docker image created by using WebLogic Image Tool is used
   * to create WebLogic domain in Operator env.
   * There are two ways to use the WLS docker image created by imagetool
   *  1. export IMAGE_NAME_WEBLOGIC = "name of your wls docker image"
   *     export IMAGE_TAG_WEBLOGIC = "version of the image"
   *  2. use the values of weblogicImageName and weblogicImageTag in OperatorWIT.properties
   *
   * @throws Exception exception
   */
  @Test
  public void testCustomImageUsed() throws Exception {
    Assume.assumeTrue(FULLTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    final Map<String, Object> domainMap = domain.getDomainMap();
    final String domainUid = domain.getDomainUid();
    final String adminServerName = (String) domainMap.get("adminServerName");
    final String adminServerPodName = domainUid + "-" + adminServerName;
    final String podNameSpace = (String) domainMap.get("namespace");
    ExecResult result = null;

    // Verify that the WebLogic docker image created by WIT is used
    StringBuffer getImageNameCmd = new StringBuffer();
    String cmd =
        getImageNameCmd
          .append("kubectl get pod ")
          .append(adminServerPodName)
          .append(" -n ")
          .append(podNameSpace)
          .append(" -o=jsonpath='{.spec.containers[*].image}'")
          .toString();
    logger.info("Command to get pod's image name: " + cmd);

    result = TestUtils.exec(cmd);

    Assume.assumeNotNull("Failed to to get pod's image name ", result);
    Assume.assumeTrue("Failed to use the image <" + WLS_IMAGE_TAG
        + "> built by imagetool", (result.stdout()).equals(WLS_IMAGE_TAG));

    logger.info("WebLogic docker image used by pod <"
        + adminServerPodName + "> is <" + result.stdout() + ">");

    logger.info("SUCCESS - " + testMethodName);
  }

  private static void buildWlsDockerImage() throws Exception {
    //build wls docker image using imagetool
    logger.info("Building a WebLogic docker image using imagetool... ");
    final String projectRoot = System.getProperty("user.dir") + "/..";

    StringBuffer buildImage = new StringBuffer();
    String cmd =
        buildImage
          .append(" sh ")
          .append(projectRoot)
          .append("/")
          .append(TEST_RESOURCE_LOC)
          .append("/imagetool/build.sh")
          .toString();
    logger.info("Command to build image name: " + cmd);

    ExecResult result = ExecCommand.exec(cmd, true);
    if (result.exitValue() != 0) {
      throw new RuntimeException(
        "FAILURE: Command "
          + cmd
          + " failed with stderr = "
          + result.stderr()
          + " \n stdout = "
          + result.stdout());
    }

    //check the image built successfully
    cmd = "docker image ls |grep " + WLS_IMAGE_NAME;
    result = ExecCommand.exec(cmd);

    Assume.assumeTrue("The image <" + WLS_IMAGE_TAG + "> doesn't exist!",
        result.exitValue() == 0);

    logger.info("A WebLogic docker image <" + WLS_IMAGE_TAG
        + "> is created successfully by imagetool! ");
  }
}