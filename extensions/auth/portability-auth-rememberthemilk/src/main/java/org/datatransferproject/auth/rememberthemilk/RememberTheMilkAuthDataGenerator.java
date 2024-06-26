/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.auth.rememberthemilk;

import com.fasterxml.jackson.xml.XmlMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.auth.rememberthemilk.model.AuthElement;
import org.datatransferproject.spi.api.auth.AuthDataGenerator;
import org.datatransferproject.spi.api.auth.AuthServiceProviderRegistry.AuthMode;
import org.datatransferproject.spi.api.types.AuthFlowConfiguration;
import org.datatransferproject.types.transfer.auth.AppCredentials;
import org.datatransferproject.types.transfer.auth.AuthData;
import org.datatransferproject.types.transfer.auth.TokenAuthData;

import java.io.IOException;
import java.net.URL;

import static org.datatransferproject.types.common.PortabilityCommon.AuthProtocol;
import static org.datatransferproject.types.common.PortabilityCommon.AuthProtocol.CUSTOM;

/*
 * {@link AuthDataGenerator} to obtain auth credentials for the Remember The Milk API.
 *
 * <p>TODO(#553): Remove code/token exchange as this will be handled by frontends.
 */
public class RememberTheMilkAuthDataGenerator implements AuthDataGenerator {
  private static final AuthProtocol AUTH_PROTOCOL = CUSTOM;
  private static final String AUTH_URL = "http://api.rememberthemilk.com/services/auth/";
  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static final String GET_TOKEN_URL = "https://api.rememberthemilk.com/services/rest/";
  private static final String GET_TOKEN_METHOD = "rtm.auth.getToken";
  private static final int SUCCESS=200;
  private final RememberTheMilkSignatureGenerator signatureGenerator;
  private final String perms;
  private final Monitor monitor;
  private final XmlMapper xmlMapper;

  public RememberTheMilkAuthDataGenerator(
      AppCredentials appCredentials, AuthMode authMode, Monitor monitor) {
    signatureGenerator = new RememberTheMilkSignatureGenerator(appCredentials);
    perms = (authMode == AuthMode.IMPORT) ? "write" : "read";
    this.monitor = monitor;
    this.xmlMapper = new XmlMapper();
  }

  @Override
  public AuthFlowConfiguration generateConfiguration(String callbackBaseUrl, String id) {
    // NOTE: callbackBaseUrl is unused. After authentication, RememberTheMilk will redirect
    // to the callback set when we configured the application. To change this visit:
    // https://www.rememberthemilk.com/help/contact/support/?ctx=api.update&report=1
    URL authUrlSigned;
    try {
      authUrlSigned = signatureGenerator.getSignature(AUTH_URL, ImmutableMap.of("perms", perms));
    } catch (Exception e) {
      monitor.severe(() -> "Error generating RememberTheMilk Authentication URL", e);
      return null;
    }

    return new AuthFlowConfiguration(authUrlSigned.toString(), getTokenUrl(), AUTH_PROTOCOL, null);
  }

  @Override
  public AuthData generateAuthData(
      String callbackUrl, String authCode, String id, AuthData initialAuthData, String extra) {
    // callbackbaseurl, id, initialAuthData and extra are not used.
    try {
      return new TokenAuthData(getToken(authCode));
    } catch (IOException e) {
      monitor.severe(() -> "Error getting RememberTheMilk AuthToken: ", e);
      return null;
    }
  }

  private String getToken(String frob) throws IOException {
    URL signedUrl =
        signatureGenerator.getSignature(
            GET_TOKEN_URL, ImmutableMap.of("frob", frob, "method", GET_TOKEN_METHOD));

    HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory();
    HttpRequest getRequest = requestFactory.buildGetRequest(new GenericUrl(signedUrl));
    HttpResponse response = getRequest.execute();
    int statusCode = response.getStatusCode();
    if (statusCode != SUCCESS) {
      throw new IOException(
          "Bad status code: " + statusCode + " error: " + response.getStatusMessage());
    }

    AuthElement authElement = xmlMapper.readValue(response.getContent(), AuthElement.class);

    Preconditions.checkState(authElement.stat.equals("ok"), "state must be ok: %s", authElement);
    Preconditions.checkState(
        !Strings.isNullOrEmpty(authElement.auth.token), "token must not be empty", authElement);
    return authElement.auth.token;
  }
}
