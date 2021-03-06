/*
 * Copyright 2015 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.gradle.azkaban;


import com.linkedin.gradle.util.HtmlUtil;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.json.JSONObject;

/**
 * AzkabanHelper is a helper class for the Azkaban Tasks.
 */
class AzkabanHelper {

  private final static Logger logger = Logging.getLogger(AzkabanHelper);

  /**
   * Helper method to make a login request to Azkaban.
   *
   * @param azkabanUrl The Azkaban server URL
   * @param userName The username
   * @param password The password
   * @return The session id from the response
   */
  static String azkabanLogin(String azkabanUrl, String userName, char[] password) {
    List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
    urlParameters.add(new BasicNameValuePair("action", "login"));
    urlParameters.add(new BasicNameValuePair("username", userName));
    urlParameters.add(new BasicNameValuePair("password", password.toString()));

    // Clear the password array as soon as it is used. There will still object references to the
    // password floating around in the JVM, but this is still a good practice.
    Arrays.fill(password, ' ' as char);

    HttpPost httpPost = new HttpPost(azkabanUrl);
    httpPost.setEntity(new UrlEncodedFormEntity(urlParameters));
    httpPost.setHeader("Accept", "*/*");
    httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded");

    HttpClient httpClient = new DefaultHttpClient();
    try {
      SSLSocketFactory socketFactory = new SSLSocketFactory(new TrustStrategy() {
        @Override
        boolean isTrusted(X509Certificate[] x509Certificates, String s)
            throws CertificateException {
          return true;
        }
      });

      Scheme scheme = new Scheme("https", 443, socketFactory);
      httpClient.getConnectionManager().getSchemeRegistry().register(scheme);
      HttpResponse response = httpClient.execute(httpPost);

      if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
        throw new GradleException("Login attempt failed.\nStatus line: " + response.getStatusLine().toString() + "\nStatus code: " + response.getStatusLine().getStatusCode());
      }

      logger.lifecycle("--------------------------------------------------------------------------------");
      logger.lifecycle(parseResponse(response.toString()));
      String result = parseContent(response.getEntity().getContent());
      // logger.lifecycle("\n" + result);  // Commented out to not display the session id on the screen
      logger.lifecycle("--------------------------------------------------------------------------------");

      // Check if the login is successful
      JSONObject jsonObj = new JSONObject(result);

      if (jsonObj.has("error")) {
        throw new GradleException(jsonObj.get("error").toString());
      }

      if (!jsonObj.has("session.id")) {
        throw new GradleException("Login attempt failed. The session ID could not be obtained.");
      }

      String sessionId = jsonObj.getString("session.id");
      saveSession(sessionId);
      return sessionId;
    }
    finally {
      httpClient.getConnectionManager().shutdown();
    }
  }

  /**
   * Gets the content from the HTTP response.
   *
   * @param The HTTP response as an input stream
   * @return The content from the response
   */
  static String parseContent(InputStream response) {
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(response));
      StringBuilder result = new StringBuilder();
      String line = null;
      while ((line = reader.readLine()) != null) {
        result.append(line);
      }
      return result.toString();
    }
    finally {
      if (reader != null) {
        reader.close();
      }
    }
  }

  /**
   * Format the response String to remove HTML tags. This will improve the readability of warning
   * messages returned from Azkaban.
   *
   * @param response The HttpResponse from the server
   * @return The parsed response
   */
  static String parseResponse(String response) {
    String newline = System.getProperty("line.separator");
    return HtmlUtil.toText(response).replaceAll("azkaban.failure.message=", newline + newline);
  }

  /**
   * Reads the session id from session.file in the user's ~/.azkaban directory.
   *
   * @return sessionId The saved session id
   */
  static String readSession() {
    File file = new File(System.getProperty("user.home") + "/.azkaban/session.properties");
    String sessionId = null;
    if (file.exists()) {
      file.withInputStream { inputStream ->
        Properties properties = new Properties();
        properties.load(inputStream);
        sessionId = properties.getProperty("sessionId");
      }
    }
    return sessionId;
  }

  /**
   * Stores the session id in a properties file under ~/.azkaban.
   *
   * @param sessionId The session id
   */
  static void saveSession(String sessionId) {
    if (!sessionId) {
      throw new GradleException("No session ID obtained to save");
    }

    File dir = new File(System.getProperty("user.home") + "/.azkaban");
    if (!dir.exists() && !dir.mkdirs()) {
      logger.error("Unable to create directory: " + dir.toString());
      return;
    }

    File file = new File(dir, "session.properties");
    if (file.exists() && !file.delete()) {
      logger.error("Unable to delete the existing file at: " + file.toString());
      return;
    }

    try {
      file.withWriter { writer ->
        Properties properties = new Properties();
        properties.setProperty("sessionId", sessionId);
        properties.store(writer, null);
      }

      // Make the file readable only by the user. The Java File API makes this a little awkward to express.
      file.setReadable(false, false);
      file.setReadable(true, true);
    }
    catch (IOException ex) {
      logger.error("Unable to store session ID to file: " + file.toString() + "\n" + ex.toString());
    }
  }

  /**
   * Configures the fields for azkabanUploadTask.
   *
   * @param azkProject The AzkabanProject
   * @return boolean to save the entered fields to .azkabanPlugin.json
   */
  static boolean configureTask(AzkabanProject azkProject) {
    def console = System.console();
    if (console == null) {
      String msg = "\nCannot access the system console. To use this task, explicitly set JAVA_HOME to the version specified in product-spec.json (at LinkedIn) and pass --no-daemon in your command.";
      throw new GradleException(msg);
    }

    logger.lifecycle("Entering interactive mode. You can use the -PskipInteractive command line parameter to skip interactive mode and ONLY read from the .azkabanPlugin.json file.\n");
    logger.lifecycle("Current Azkaban project name: ${azkProject.azkabanProjName}");
    logger.lifecycle("Current Azkaban URL: ${azkProject.azkabanUrl}");
    logger.lifecycle("Current Azkaban user name: ${azkProject.azkabanUserName}");
    logger.lifecycle("Current Azkaban Zip task: ${azkProject.azkabanZipTask}");

    try {
      logger.lifecycle("\nWant to change any of the above? [y/N]: ");
      def input = console.readLine();

      if (input.toString().trim().toLowerCase() == 'y') {
        input = console.readLine("New Azkaban project name [enter to leave unchanged]: ");
        if (input != null && !input.isEmpty()) {
          azkProject.azkabanProjName = input.toString();
        }

        input = console.readLine("New Azkaban URL [enter to leave unchanged]: ");
        if (input != null && !input.isEmpty()) {
          azkProject.azkabanUrl = input.toString();
        }

        input = console.readLine("New Azkaban user name [enter to leave unchanged]: ");
        if (input != null && !input.isEmpty()) {
          azkProject.azkabanUserName = input.toString();
        }

        input = console.readLine("New Azkaban Zip task (run 'ligradle tasks' to find existing Zip tasks) [enter to leave unchanged]: ");
        if (input != null && !input.isEmpty()) {
          azkProject.azkabanZipTask = input.toString();
        }

        input = console.readLine("Save these changes to the .azkabanPlugin.json file? [Y/n]: ");
        if (input.toString().trim().toLowerCase() == 'n') {
          return false;
        } else {
          return true;
        }
      }
    } catch (IOException ex) {
      logger.error("Failed in taking input from user in Interactive mode." + "\n" + ex.toString());
    }

    return false;
  }
}
