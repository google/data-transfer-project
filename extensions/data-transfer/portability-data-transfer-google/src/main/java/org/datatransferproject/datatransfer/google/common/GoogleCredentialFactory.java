/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.datatransfer.google.common;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.CredentialRefreshListener;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import java.io.IOException;
import com.google.api.services.people.v1.PeopleService;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.types.transfer.auth.AppCredentials;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

/**
 * Factory for creating {@link Credential} objects from {@link TokensAndUrlAuthData}.
 */
public class GoogleCredentialFactory {

  // TODO: Determine correct duration in production
  private static final long EXPIRE_TIME_IN_SECONDS = 0L;

  private final HttpTransport httpTransport;
  private final JsonFactory jsonFactory;
  private final AppCredentials appCredentials;
  private final Monitor monitor;

  public GoogleCredentialFactory(
      HttpTransport httpTransport, JsonFactory jsonFactory, AppCredentials appCredentials,
      Monitor monitor) {
    this.httpTransport = httpTransport;
    this.jsonFactory = jsonFactory;
    this.appCredentials = appCredentials;
    this.monitor = monitor;
  }

  public HttpTransport getHttpTransport() {
    return httpTransport;
  }

  public JsonFactory getJsonFactory() {
    return jsonFactory;
  }

  /**
   * Creates a {@link Credential} objects with the given {@link TokensAndUrlAuthData} which supports
   * refreshing tokens.
   */
  public Credential createCredential(TokensAndUrlAuthData authData) {
    return new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
        .setTransport(httpTransport)
        .setJsonFactory(jsonFactory)
        .setClientAuthentication(
            new ClientParametersAuthentication(appCredentials.getKey(), appCredentials.getSecret()))
        .setTokenServerEncodedUrl(authData.getTokenServerEncodedUrl())
        .addRefreshListener(
            new CredentialRefreshListener() {
              @Override
              public void onTokenResponse(Credential credential, TokenResponse tokenResponse)
                  throws IOException {
                monitor.info(() -> "Successfully refreshed token");
              }

              @Override
              public void onTokenErrorResponse(Credential credential,
                  TokenErrorResponse tokenErrorResponse) throws IOException {
                monitor
                    .info(() -> "Error while refreshing token: " + tokenErrorResponse.getError());
              }
            })
        .build()
        .setAccessToken(authData.getAccessToken())
        .setRefreshToken(authData.getRefreshToken())
        .setExpiresInSeconds(EXPIRE_TIME_IN_SECONDS);
  }

  /** Refreshes and updates the given credential */
  public Credential refreshCredential(Credential credential)
      throws IOException, InvalidTokenException {
    try {
      TokenResponse tokenResponse =
          new RefreshTokenRequest(
                  httpTransport,
                  jsonFactory,
                  new GenericUrl(credential.getTokenServerEncodedUrl()),
                  credential.getRefreshToken())
              .setClientAuthentication(credential.getClientAuthentication())
              .setRequestInitializer(credential.getRequestInitializer())
              .execute();

      return credential.setFromTokenResponse(tokenResponse);
    } catch (TokenResponseException e) {
      TokenErrorResponse details = e.getDetails();
      if (details != null && details.getError().equals("invalid_grant")) {
        throw new InvalidTokenException("Unable to refresh token.", e);
      } else {
        throw e;
      }
    }
  }
  public  PeopleService createPeopleService(TokensAndUrlAuthData authData) {
    Credential credential = createCredential(authData);
    return new PeopleService.Builder(
            getHttpTransport(), getJsonFactory(), credential)
            .setApplicationName(GoogleStaticObjects.APP_NAME)
            .build();
  }

}
