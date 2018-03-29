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
package org.dataportabilityproject.gateway.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.dataportabilityproject.api.launcher.TypeManager;
import org.dataportabilityproject.gateway.reference.ReferenceApiUtils.FrontendConstantUrls;
import org.dataportabilityproject.gateway.reference.ReferenceApiUtils.HttpMethods;
import org.dataportabilityproject.security.DecrypterFactory;
import org.dataportabilityproject.security.EncrypterFactory;
import org.dataportabilityproject.security.SymmetricKeyGenerator;
import org.dataportabilityproject.spi.cloud.storage.JobStore;
import org.dataportabilityproject.spi.cloud.types.PortabilityJob;
import org.dataportabilityproject.spi.gateway.auth.AuthDataGenerator;
import org.dataportabilityproject.spi.gateway.auth.AuthServiceProviderRegistry;
import org.dataportabilityproject.spi.gateway.auth.AuthServiceProviderRegistry.AuthMode;
import org.dataportabilityproject.types.transfer.auth.AuthData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HttpHandler for callbacks from Oauth1 authorization flow. Redirects client request to: - the next
 * authorization (if this is after the source service auth) or - the copy page (if this is after the
 * destination service auth)
 */
final class OauthCallbackHandler implements HttpHandler {
  public static final String PATH = "/callback1/";
  // TODO: obtain from flags
  private static final boolean IS_LOCAL = true;
  private final Logger logger = LoggerFactory.getLogger(OauthCallbackHandler.class);
  private final AuthServiceProviderRegistry registry;
  private final JobStore store;
  private final SymmetricKeyGenerator symmetricKeyGenerator;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String baseApiUrl;

  @Inject
  OauthCallbackHandler(
      JobStore store,
      AuthServiceProviderRegistry registry,
      SymmetricKeyGenerator symmetricKeyGenerator,
      TypeManager typeManager,
      @Named("baseUrl") String baseUrl,
      @Named("baseApiUrl") String baseApiUrl) {
    this.registry = registry;
    this.store = store;
    this.symmetricKeyGenerator = symmetricKeyGenerator;
    this.objectMapper = typeManager.getMapper();
    this.baseUrl = baseUrl;
    this.baseApiUrl = baseApiUrl;

    logger.debug("Using jobstore: {} in DataTransferHandler", store);

  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    // Add .* to resource path as this path will be of the form /callback1/SERVICEPROVIDER
    Preconditions.checkArgument(
        ReferenceApiUtils.validateRequest(exchange, HttpMethods.GET, PATH + ".*"));
    logger.debug("received request: {}", exchange.getRequestURI());

    String redirect = handleExchange(exchange);
    logger.debug("redirecting to {}", redirect);
    exchange.getResponseHeaders().set(HttpHeaders.LOCATION, redirect);
    exchange.sendResponseHeaders(303, -1);
  }

  private String handleExchange(HttpExchange exchange) throws IOException {
    String redirect = "/error";

    try {
      Headers requestHeaders = exchange.getRequestHeaders();

      // Get the URL for the request - needed for the authorization.

      String requestURL =
          ReferenceApiUtils.createURL(
              requestHeaders.getFirst(HttpHeaders.HOST),
              exchange.getRequestURI().toString(),
              IS_LOCAL);

      Map<String, String> requestParams = ReferenceApiUtils.getRequestParams(exchange);

      String encodedIdCookie = ReferenceApiUtils.getCookie(requestHeaders, JsonKeys.ID_COOKIE_KEY);
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(encodedIdCookie), "Missing encodedIdCookie");

      String oauthToken = requestParams.get("oauth_token");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(oauthToken), "Missing oauth_token");

      String oauthVerifier = requestParams.get("oauth_verifier");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(oauthVerifier), "Missing oauth_verifier");

      // Valid job must be present
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(encodedIdCookie), "Encoded Id cookie required");
      UUID jobId = ReferenceApiUtils.decodeJobId(encodedIdCookie);

      PortabilityJob job = store.findJob(jobId);
      logger.debug("Found job: {}->{} in OCH", jobId, job);

      Preconditions.checkNotNull(job, "existing job not found for jobId: %s", jobId);

      // TODO: Determine service from job or from authUrl path?
      AuthMode authMode = ReferenceApiUtils.getAuthMode(exchange.getRequestHeaders());
      String service = (authMode == AuthMode.EXPORT) ? job.exportService() : job.importService();
      Preconditions.checkState(
          !Strings.isNullOrEmpty(service),
          "service not found, service: %s authMode: %s, jobId: %s",
          service,
          authMode,
          jobId.toString());

      AuthDataGenerator generator =
          registry.getAuthDataGenerator(service, job.transferDataType(), authMode);
      Preconditions.checkNotNull(
          generator,
          "Generator not found for type: %s, service: %s",
          job.transferDataType(),
          service);

      // Obtain the session key for this job
      String encodedSessionKey = job.jobAuthorization().sessionSecretKey();
      SecretKey key =
          symmetricKeyGenerator.parse(BaseEncoding.base64Url().decode(encodedSessionKey));

      // Retrieve initial auth data, if it existed
      AuthData initialAuthData = null;
      String encryptedInitialAuthData =
          (authMode == AuthMode.EXPORT)
              ? job.jobAuthorization().encryptedInitialExportAuthData()
              : job.jobAuthorization().encryptedInitialImportAuthData();
      if (encryptedInitialAuthData != null) {
        // Retrieve and parse the session key from the job
        // Decrypt and deserialize the object
        String serialized = DecrypterFactory.create(key).decrypt(encryptedInitialAuthData);
        initialAuthData = objectMapper.readValue(serialized, AuthData.class);
      }

      Preconditions.checkNotNull(
          initialAuthData, "Initial AuthData expected during Oauth 1.0 flow");

      // TODO: Use UUID instead of UUID.toString()
      // Generate auth data
      AuthData authData =
          generator.generateAuthData(
              baseApiUrl, oauthVerifier, jobId.toString(), initialAuthData, null);
      Preconditions.checkNotNull(authData, "Auth data should not be null");

      // Serialize and encrypt the auth data
      String serialized = objectMapper.writeValueAsString(authData);
      String encryptedAuthData = EncrypterFactory.create(key).encrypt(serialized);
      // Set new cookie
      ReferenceApiUtils.setCookie(exchange.getResponseHeaders(), encryptedAuthData, authMode);

      redirect =
          baseUrl
              + ((authMode == AuthMode.EXPORT)
                  ? FrontendConstantUrls.URL_NEXT_PAGE
                  : FrontendConstantUrls.URL_COPY_PAGE);
    } catch (Exception e) {
      logger.error("Error handling request", e);
      throw e;
    }

    return redirect;
  }
}
