/*
 * Copyright 2023 The Data Transfer Project Authors.
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

package org.datatransferproject.datatransfer.apple.photos;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CONFLICT;
import static org.apache.http.HttpStatus.SC_INSUFFICIENT_STORAGE;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_OK;
import static org.apache.http.HttpStatus.SC_PRECONDITION_FAILED;
import static org.apache.http.HttpStatus.SC_SERVICE_UNAVAILABLE;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.apple.AppleBaseInterface;
import org.datatransferproject.datatransfer.apple.constants.ApplePhotosConstants;
import org.datatransferproject.datatransfer.apple.constants.Headers;
import org.datatransferproject.datatransfer.apple.exceptions.AppleContentException;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.AuthorizeUploadRequest;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.AuthorizeUploadResponse;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.CreateAlbumsRequest;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.CreateAlbumsResponse;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.CreateMediaRequest;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.CreateMediaResponse;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.GetUploadUrlsRequest;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.GetUploadUrlsResponse;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.NewMediaRequest;
import org.datatransferproject.datatransfer.apple.photos.photosproto.PhotosProtocol.NewPhotoAlbumRequest;
import org.datatransferproject.datatransfer.apple.photos.streaming.StreamingContentClient;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;
import org.datatransferproject.spi.transfer.types.DestinationMemoryFullException;
import org.datatransferproject.spi.transfer.types.DestinationNotFoundException;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.spi.transfer.types.PermissionDeniedException;
import org.datatransferproject.spi.transfer.types.UnconfirmedUserException;
import org.datatransferproject.transfer.JobMetadata;
import org.datatransferproject.types.common.DownloadableFile;
import org.datatransferproject.types.common.DownloadableItem;
import org.datatransferproject.types.common.models.media.MediaAlbum;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.videos.VideoModel;
import org.datatransferproject.types.transfer.auth.AppCredentials;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * An interface that is synonymous to HTTP client to interact with the Apple Photos APIs.
 */
public class AppleMediaInterface implements AppleBaseInterface {

  protected String baseUrl;
  protected AppCredentials appCredentials;
  protected String exportingService;
  protected Monitor monitor;
  protected TokensAndUrlAuthData authData;

  public AppleMediaInterface(
      @NotNull final TokensAndUrlAuthData authData,
      @NotNull final AppCredentials appCredentials,
      @NotNull final String exportingService,
      @NotNull final Monitor monitor) {
    this.authData = authData;
    this.appCredentials = appCredentials;
    this.exportingService = exportingService;
    this.monitor = monitor;
    this.baseUrl = "https://datatransfer.apple.com/photos/";
  }

  public CreateAlbumsResponse createAlbums(
      @NotNull final String jobId,
      @NotNull final String dataClass,
      @NotNull final Collection<MediaAlbum> mediaAlbums)
      throws IOException, CopyExceptionWithFailureReason {
    final CreateAlbumsRequest.Builder createAlbumsRequestBuilder = CreateAlbumsRequest.newBuilder();

    // take jobId as importSessionId
    createAlbumsRequestBuilder.setImportSessionId(jobId);

    // validate export service
    if (JobMetadata.isInitialized() && JobMetadata.getExportService() != null) {
      createAlbumsRequestBuilder.setExportService(JobMetadata.getExportService());
    }

    createAlbumsRequestBuilder.setDataClass(dataClass);

    createAlbumsRequestBuilder.addAllNewPhotoAlbumRequests(
      mediaAlbums.stream()
        .map(
          mediaAlbum -> NewPhotoAlbumRequest.newBuilder()
            .setDataId(mediaAlbum.getId())
            .setName(Optional.ofNullable(mediaAlbum.getName()).orElse(""))
            .build())
        .collect(Collectors.toList()));
    CreateAlbumsRequest createAlbumsRequest = createAlbumsRequestBuilder.build();
    final byte[] payload = createAlbumsRequest.toByteArray();
    final byte[] responseData = makePhotosServicePostRequest(baseUrl + "createalbums", payload);
    return CreateAlbumsResponse.parseFrom(responseData);
  }

  public GetUploadUrlsResponse getUploadUrl(
      @NotNull final String jobId,
      @NotNull final String dataClass,
      @NotNull final List<String> dataIds)
      throws IOException, CopyExceptionWithFailureReason {
    final List<AuthorizeUploadRequest> uploadRequestList =
        dataIds.stream()
            .map(dataId -> AuthorizeUploadRequest.newBuilder().setDataId(dataId).build())
            .collect(Collectors.toList());

    final GetUploadUrlsRequest.Builder getUploadUrlsRequestBuilder =
        GetUploadUrlsRequest.newBuilder();

    if (JobMetadata.getExportService() != null) {
      getUploadUrlsRequestBuilder.setExportService(JobMetadata.getExportService());
    }

    getUploadUrlsRequestBuilder.setDataClass(dataClass);

    getUploadUrlsRequestBuilder.setImportSessionId(jobId).addAllUploadRequests(uploadRequestList);

    final byte[] payload = getUploadUrlsRequestBuilder.build().toByteArray();
    final byte[] getUploadUrlsResponseData =
        makePhotosServicePostRequest(baseUrl + "getuploadurls", payload);
    final GetUploadUrlsResponse getUploadUrlsResponse =
        GetUploadUrlsResponse.parseFrom(getUploadUrlsResponseData);
    return getUploadUrlsResponse;
  }

  // download from external server then upload to apple
  public Map<String, DownUpResult> uploadContent(
      @NotNull final Map<String, String> dataIdToDownloadURLMap,
      @NotNull final List<AuthorizeUploadResponse> authorizeUploadResponseList) {
    final Map<String, DownUpResult> dataIdToUploadResponseMap = new HashMap<>();
    for (AuthorizeUploadResponse authorizeUploadResponse : authorizeUploadResponseList) {
      final String dataId = authorizeUploadResponse.getDataId();
      final String downloadURL = dataIdToDownloadURLMap.get(dataId);
      try (final StreamingContentClient downloadClient =
              new StreamingContentClient(
                  downloadURL, StreamingContentClient.StreamingMode.DOWNLOAD, monitor);
          final StreamingContentClient uploadClient =
              new StreamingContentClient(
                  authorizeUploadResponse.getUploadUrl(),
                  StreamingContentClient.StreamingMode.UPLOAD,
                  monitor)) {

        final int maxRequestBytes = ApplePhotosConstants.contentRequestLength;
        int totalSize = 0;
        for (byte[] data = downloadClient.downloadBytes(maxRequestBytes);
            data != null;
            data = downloadClient.downloadBytes(maxRequestBytes)) {
          totalSize += data.length;

          if (totalSize > ApplePhotosConstants.maxMediaTransferByteSize) {
            uploadClient.completeUpload();
            throw new AppleContentException(getApplePhotosImportThrowingMessage("file too large to import to Apple", ImmutableMap.of(
                    AuditKeys.dataId, Optional.of(dataId),
                    AuditKeys.downloadURL, Optional.of(downloadURL),
                    AuditKeys.uploadUrl, Optional.of(authorizeUploadResponse.getUploadUrl()))));
          }

          uploadClient.uploadBytes(data);
          if (data.length < maxRequestBytes) {
            break;
          }
        }
        final String singleFileUploadResponse = uploadClient.completeUpload();
        dataIdToUploadResponseMap.put(dataId, DownUpResult.ofDataId(singleFileUploadResponse));
      } catch (AppleContentException e) {
        dataIdToUploadResponseMap.put(dataId, DownUpResult.ofError(e));
      }
    }
    return dataIdToUploadResponseMap;
  }

  public CreateMediaResponse createMedia(
      @NotNull final String jobId,
      @NotNull final String dataClass,
      @NotNull final List<NewMediaRequest> newMediaRequestList)
      throws IOException, CopyExceptionWithFailureReason {
    // createMedia
    final CreateMediaRequest.Builder createMediaRequestBuilder = CreateMediaRequest.newBuilder();

    // take jobId as importSessionId
    createMediaRequestBuilder.setImportSessionId(jobId);
    createMediaRequestBuilder.setDataClass(dataClass);
    createMediaRequestBuilder.addAllNewMediaRequests(newMediaRequestList);
    CreateMediaRequest createMediaRequest = createMediaRequestBuilder.build();

    for (NewMediaRequest newMediaRequest: newMediaRequestList) {
      monitor.debug(() -> "AppleMediaImporter send data to Apple Photos Service",
              AuditKeys.dataId, newMediaRequest.getDataId(),
              AuditKeys.updatedTimeInMs, newMediaRequest.getCreationDateInMillis());
    }

    final byte[] payload = createMediaRequest.toByteArray();

    final byte[] responseData = makePhotosServicePostRequest(baseUrl + "createmedia", payload);
    return CreateMediaResponse.parseFrom(responseData);
  }

  private String sendPostRequest(@NotNull String url, @NotNull final byte[] requestData)
      throws IOException, CopyExceptionWithFailureReason {

    final String appleRequestUUID = UUID.randomUUID().toString();
    final UUID jobId = JobMetadata.getJobId();
    monitor.info(
      () -> "POST Request from AppleMediaInterface",
      Headers.CORRELATION_ID, appleRequestUUID,
      AuditKeys.uri, url,
      AuditKeys.jobId, jobId.toString());

    HttpURLConnection con = null;
    String responseString = "";
    try {
      URL applePhotosUrl = new URL(url);
      con = (HttpURLConnection) applePhotosUrl.openConnection();
      con.setDoOutput(true);
      con.setRequestMethod("POST");
      con.setRequestProperty(Headers.AUTHORIZATION.getValue(), authData.getAccessToken());
      con.setRequestProperty(Headers.CORRELATION_ID.getValue(), appleRequestUUID);
      if (url.contains(baseUrl)) {
        // which means we are not sending request to get access token, the
        // contentStream is not filled with params, but with DTP transfer request
        con.setRequestProperty(Headers.CONTENT_TYPE.getValue(), "");
      }
      IOUtils.write(requestData, con.getOutputStream());
      responseString = IOUtils.toString(con.getInputStream(), StandardCharsets.ISO_8859_1);

    } catch (IOException e) {
      monitor.severe(
        () -> "Exception from POST in AppleMediaInterface",
        Headers.CORRELATION_ID.getValue(), appleRequestUUID,
        AuditKeys.jobId, jobId.toString(),
        AuditKeys.error, e.getMessage(),
        AuditKeys.errorCode, con.getResponseCode(),
      e);

      convertAndThrowException(e, con);
    } finally {
      con.disconnect();
    }
    return responseString;
  }

  private void convertAndThrowException(@NotNull final IOException e, @NotNull final HttpURLConnection con)
      throws IOException, CopyExceptionWithFailureReason {

    switch (con.getResponseCode()) {
      case SC_UNAUTHORIZED:
        throw new UnconfirmedUserException(getApplePhotosImportThrowingMessage("Unauthorized iCloud User"), e);
      case SC_PRECONDITION_FAILED:
        throw new PermissionDeniedException(getApplePhotosImportThrowingMessage("Permission Denied"), e);
      case SC_NOT_FOUND:
        throw new DestinationNotFoundException(getApplePhotosImportThrowingMessage("iCloud Photos Library not found"), e);
      case SC_INSUFFICIENT_STORAGE:
        throw new DestinationMemoryFullException(getApplePhotosImportThrowingMessage("iCloud Storage is full"), e);
      case SC_SERVICE_UNAVAILABLE:
        throw new IOException(getApplePhotosImportThrowingMessage("DTP import service unavailable"), e);
      case SC_BAD_REQUEST:
        throw new IOException(getApplePhotosImportThrowingMessage("Bad request sent to iCloud Photos import api"), e);
      case SC_INTERNAL_SERVER_ERROR:
        throw new IOException(getApplePhotosImportThrowingMessage("Internal server error in iCloud Photos service"), e);
      case SC_OK:
        break;
      default:
        throw e;
    }
  }

  public byte[] makePhotosServicePostRequest(
      @NotNull final String url, @NotNull final byte[] requestData)
      throws IOException, CopyExceptionWithFailureReason {
    byte[] responseData = null;
    try {
      final String responseString = sendPostRequest(url, requestData);
      responseData = responseString.getBytes(StandardCharsets.ISO_8859_1);
    } catch (CopyExceptionWithFailureReason e) {
      if (e instanceof UnconfirmedUserException
          || e instanceof PermissionDeniedException) {
        refreshTokens();
        final String responseString = sendPostRequest(url, requestData);
        responseData = responseString.getBytes(StandardCharsets.ISO_8859_1);
      } else {
        throw e;
      }
    }
    return responseData;
  }

  private void refreshTokens() throws InvalidTokenException {
    final String refreshToken = authData.getRefreshToken();
    final String refreshUrlString = authData.getTokenServerEncodedUrl();
    final String clientId = appCredentials.getKey();
    final String clientSecret = appCredentials.getSecret();

    final Map<String, String> parameters = new HashMap<String, String>();
    parameters.put("client_id", clientId);
    parameters.put("client_secret", clientSecret);
    parameters.put("grant_type", "refresh_token");
    parameters.put("refresh_token", refreshToken);
    StringJoiner sj = new StringJoiner("&");
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      sj.add(entry.getKey() + "=" + entry.getValue());
    }

    final byte[] requestData = sj.toString().getBytes(StandardCharsets.ISO_8859_1);
    try {
      final String responseString = sendPostRequest(refreshUrlString, requestData);
      final JSONParser parser = new JSONParser();
      final JSONObject json = (JSONObject) parser.parse(responseString);
      final String accessToken = (String) json.get("access_token");
      this.authData = new TokensAndUrlAuthData(accessToken, refreshToken, refreshUrlString);

      monitor.debug(() -> "Successfully refreshed Apple token");

    } catch (ParseException | IOException | CopyExceptionWithFailureReason e) {

      throw new InvalidTokenException(getApplePhotosImportThrowingMessage("Unable to refresh Apple token"), e);
    }
  }

  public static NewMediaRequest createNewMediaRequest(
      @Nullable final String dataId,
      @Nullable final String filename,
      @Nullable final String description,
      @Nullable final String albumId,
      @Nullable final String mediaType,
      @Nullable final String encodingFormat,
      @Nullable final Long creationDateInMillis,
      @Nullable final String singleFileUploadResponse,
      final boolean isFavorite) {

    final NewMediaRequest.Builder newMediaRequest = NewMediaRequest.newBuilder();

    if (dataId != null) {
      newMediaRequest.setDataId(dataId);
    }

    if (filename != null) {
      newMediaRequest.setFilename(filename);
    }

    if (singleFileUploadResponse != null) {
      newMediaRequest.setSingleFileUploadResponse(singleFileUploadResponse);
    }

    if (description != null) {
      newMediaRequest.setDescription(description);
    }

    if (albumId != null) {
      newMediaRequest.setAlbumId(albumId);
    }

    if (creationDateInMillis != null) {
      newMediaRequest.setCreationDateInMillis(creationDateInMillis);
    }

    if (mediaType != null) {
      newMediaRequest.setMediaType(mediaType);
    }

    if (encodingFormat != null) {
      newMediaRequest.setEncodingFormat(encodingFormat);
    }

    newMediaRequest.setIsFavorite(isFavorite == true ? 1L: 0L);

    return newMediaRequest.build();
  }

  public int importAlbums(
      final UUID jobId,
      IdempotentImportExecutor idempotentImportExecutor,
      Collection<MediaAlbum> mediaAlbums,
      @NotNull final String dataClass)
      throws Exception {
    AtomicInteger successAlbumsCount = new AtomicInteger(0);
    final Map<String, MediaAlbum> dataIdToMediaAlbum =
        mediaAlbums.stream().collect(Collectors.toMap(MediaAlbum::getId, mediaAlbum -> mediaAlbum));

    UnmodifiableIterator<List<MediaAlbum>> batches =
        Iterators.partition(mediaAlbums.iterator(), ApplePhotosConstants.maxNewAlbumRequests);
    while (batches.hasNext()) {
      final PhotosProtocol.CreateAlbumsResponse createAlbumsResponse =
          createAlbums(jobId.toString(), dataClass, batches.next());
      for (PhotosProtocol.NewPhotoAlbumResponse newPhotoAlbumResponse :
          createAlbumsResponse.getNewPhotoAlbumResponsesList()) {
        final String dataId = newPhotoAlbumResponse.getDataId();
        final MediaAlbum mediaAlbum = dataIdToMediaAlbum.get(dataId);
        if (newPhotoAlbumResponse.hasStatus()
            && newPhotoAlbumResponse.getStatus().getCode() == SC_OK) {
          successAlbumsCount.getAndIncrement();
          idempotentImportExecutor.executeAndSwallowIOExceptions(
            mediaAlbum.getId(),
            mediaAlbum.getName(),
            () -> {
              monitor.debug(
                () -> "Apple importing album",
                AuditKeys.jobId, jobId,
                AuditKeys.albumId, dataId,
                AuditKeys.recordId, newPhotoAlbumResponse.getRecordId());
              return newPhotoAlbumResponse.getRecordId();
            });
        } else {
          idempotentImportExecutor.executeAndSwallowIOExceptions(
            mediaAlbum.getId(),
            mediaAlbum.getName(),
            () -> {
              throw new IOException(getApplePhotosImportThrowingMessage("Fail to create album",
                      ImmutableMap.of(
                              AuditKeys.errorCode, Optional.of(String.valueOf(newPhotoAlbumResponse.getStatus().getCode())),
                              AuditKeys.jobId, Optional.of(jobId.toString()),
                              AuditKeys.albumId, Optional.of(mediaAlbum.getId()))));
            });
        }
      }
    }
    return successAlbumsCount.get();
  }

  // In current logic, we will continue to import the other media when we meet an error. We will
  // save then throw the error in the end.
  public Map<String, Long> importAllMedia(
      UUID jobId,
      IdempotentImportExecutor idempotentImportExecutor,
      Collection<? extends DownloadableFile> downloadableFiles,
      @NotNull final String dataClass)
      throws Exception {
    long successMediaCount = 0;
    long successMediaSize = 0;
    // todo: Currently we won't fail photo import if its album hasn't been imported
    List<DownloadableFile> newFiles =
        downloadableFiles.stream()
            .filter(
                downloadableFile ->
                    !idempotentImportExecutor.isKeyCached(downloadableFile.getIdempotentId()))
            .collect(Collectors.toList());

    UnmodifiableIterator<List<DownloadableFile>> batches =
        Iterators.partition(newFiles.iterator(), ApplePhotosConstants.maxNewMediaRequests);
    while (batches.hasNext()) {
      final Map<String, Long> batchImportResults =
          importMediaBatch(jobId, batches.next(), idempotentImportExecutor, dataClass);
      successMediaSize += batchImportResults.get(ApplePhotosConstants.BYTES_KEY);
      successMediaCount += batchImportResults.get(ApplePhotosConstants.COUNT_KEY);
    }

    final Map<String, Long> importResults =
        new ImmutableMap.Builder<String, Long>()
            .put(ApplePhotosConstants.BYTES_KEY, successMediaSize)
            .put(ApplePhotosConstants.COUNT_KEY, successMediaCount)
            .build();
    return importResults;
  }

  // return {BYTES_KEY: Long, COUNT_KEY: Long}
  Map<String, Long> importMediaBatch(
      UUID jobId,
      List<DownloadableFile> downloadableFiles,
      IdempotentImportExecutor idempotentImportExecutor,
      @NotNull final String dataClass)
      throws Exception {
    final Map<String, DownloadableFile> dataIdToDownloadableFiles =
        downloadableFiles.stream()
            .collect(
                Collectors.toMap(
                    AppleMediaInterface::getDataId, downloadableFile -> downloadableFile));

    // get upload url
    final PhotosProtocol.GetUploadUrlsResponse getUploadUrlsResponse =
        getUploadUrl(
            jobId.toString(),
            dataClass,
            downloadableFiles.stream()
                .map(AppleMediaInterface::getDataId)
                .collect(Collectors.toList()));
    final List<PhotosProtocol.AuthorizeUploadResponse> successAuthorizeUploadResponseList =
        new ArrayList<>();
    for (PhotosProtocol.AuthorizeUploadResponse authorizeUploadResponse :
        getUploadUrlsResponse.getUrlResponsesList()) {
      final String dataId = authorizeUploadResponse.getDataId();
      if (authorizeUploadResponse.hasStatus()
          && authorizeUploadResponse.getStatus().getCode() == SC_OK) {
        successAuthorizeUploadResponseList.add(authorizeUploadResponse);
      } else {
        // collect errors in get upload url
        final DownloadableFile downloadableFile = dataIdToDownloadableFiles.get(dataId);
        idempotentImportExecutor.executeAndSwallowIOExceptions(
          downloadableFile.getIdempotentId(),
          downloadableFile.getName(),
          () -> {
            throw new IOException(
                    getApplePhotosImportThrowingMessage(
                            "Fail to get upload url", ImmutableMap.of(
                                    AuditKeys.errorCode, Optional.of(String.valueOf(authorizeUploadResponse.getStatus().getCode())),
                                    AuditKeys.jobId, Optional.of(jobId.toString()),
                                    AuditKeys.dataId, Optional.of(getDataId(downloadableFile)),
                                    AuditKeys.albumId, Optional.ofNullable(downloadableFile.getFolderId()))));
          });
      }
    }

    // download then upload content
    final Map<String, DownUpResult> dataIdToUploadResponse =
      uploadContent(
        dataIdToDownloadableFiles.values().stream()
          .collect(Collectors
            .toMap(
              AppleMediaInterface::getDataId,
              DownloadableItem::getFetchableUrl)),
        successAuthorizeUploadResponseList);


    // Tell DTP about errors and prep for NewMediaRequest calls for the successes
    final List<NewMediaRequest> newMediaRequestList = new ArrayList<>();
    for (AuthorizeUploadResponse authorizeUploadResponse : successAuthorizeUploadResponseList) {
      final String dataId = authorizeUploadResponse.getDataId();
      final DownloadableFile downloadableFile = dataIdToDownloadableFiles.get(dataId);
      DownUpResult result =
          checkNotNull(
              dataIdToUploadResponse.get(dataId), "somehow missing result for dataid=%s", dataId);
      // generate newMediaRequest
      if (result.isOk()) {
        String filename = downloadableFile.getName();
        String description = getDescription(downloadableFile);
        String mediaType = downloadableFile.getMimeType();
        String albumId = downloadableFile.getFolderId();
        Long creationDateInMillis = getUploadedTime(downloadableFile);
        boolean isFavorite = false;
        if (downloadableFile instanceof VideoModel) {
          isFavorite = ((VideoModel) downloadableFile).getFavoriteInfo().getFavorited();
        } else if (downloadableFile instanceof PhotoModel) {
          isFavorite = ((PhotoModel) downloadableFile).getFavoriteInfo().getFavorited();
        }
        newMediaRequestList.add(
            AppleMediaInterface.createNewMediaRequest(
                dataId,
                filename,
                description,
                albumId,
                mediaType,
                null,
                creationDateInMillis,
                result.successDataId(),
                    isFavorite));
      } else {
        // collect errors in upload content
        idempotentImportExecutor.executeAndSwallowIOExceptions(
            downloadableFile.getIdempotentId(),
            downloadableFile.getName(),
            () -> {
              throw new IOException(
                  getApplePhotosImportThrowingMessage(
                      "Fail to upload content",
                      ImmutableMap.of(
                          AuditKeys.jobId, Optional.of(jobId.toString()),
                          AuditKeys.dataId, Optional.of(getDataId(downloadableFile)),
                          AuditKeys.albumId, Optional.ofNullable(downloadableFile.getFolderId()))),
                  result.error());
            });
      }
    }

    final PhotosProtocol.CreateMediaResponse createMediaResponse =
        createMedia(jobId.toString(), dataClass, newMediaRequestList);

    // collect results in create media
    long totalBytes = 0L;
    long mediaCount = 0;
    for (PhotosProtocol.NewMediaResponse newMediaResponse :
        createMediaResponse.getNewMediaResponsesList()) {
      final String dataId = newMediaResponse.getDataId();
      final DownloadableFile downloadableFile = dataIdToDownloadableFiles.get(dataId);
      if (newMediaResponse.hasStatus()
          && newMediaResponse.getStatus().getCode() == SC_OK) {
        mediaCount += 1;
        totalBytes += newMediaResponse.getFilesize();
        idempotentImportExecutor.executeAndSwallowIOExceptions(
          downloadableFile.getIdempotentId(),
          downloadableFile.getName(),
          () -> {
            monitor.debug(
              () -> "Apple importing photo",
              AuditKeys.jobId, jobId,
              AuditKeys.dataId, getDataId(downloadableFile),
              AuditKeys.albumId, downloadableFile.getFolderId(),
              AuditKeys.recordId, newMediaResponse.getRecordId());
            return newMediaResponse.getRecordId();
          });
      } else if (newMediaResponse.getStatus().getCode() == SC_CONFLICT) {
        idempotentImportExecutor.executeAndSwallowIOExceptions(
          downloadableFile.getIdempotentId(),
          downloadableFile.getName(),
          () -> {
            monitor.debug(
              () -> "duplicated photo",
              AuditKeys.jobId, jobId,
              AuditKeys.dataId, getDataId(downloadableFile),
              AuditKeys.albumId, downloadableFile.getFolderId(),
              AuditKeys.recordId, newMediaResponse.getRecordId());
            return newMediaResponse.getRecordId();
          });
      } else {
        idempotentImportExecutor.executeAndSwallowIOExceptions(
          downloadableFile.getIdempotentId(),
          downloadableFile.getName(),
          () -> {
            throw new IOException(
                    getApplePhotosImportThrowingMessage(
                "Fail to create media", ImmutableMap.of(
                            AuditKeys.errorCode, Optional.of(String.valueOf(newMediaResponse.getStatus().getCode())),
                            AuditKeys.jobId, Optional.of(jobId.toString()),
                            AuditKeys.dataId, Optional.of(getDataId(downloadableFile)),
                            AuditKeys.albumId, Optional.ofNullable(downloadableFile.getFolderId()))));
          });
      }
    }

    // return count and bytes
    monitor.info(
        () -> "Apple imported photo batch",
        AuditKeys.jobId, jobId,
        AuditKeys.totalFilesCount, mediaCount,
        AuditKeys.bytesExported, totalBytes);

    final Map<String, Long> batchImportResults =
        new ImmutableMap.Builder<String, Long>()
            .put(ApplePhotosConstants.BYTES_KEY, totalBytes)
            .put(ApplePhotosConstants.COUNT_KEY, mediaCount)
            .build();
    return batchImportResults;
  }

  private static String getDataId(DownloadableFile downloadableFile) {
    if (downloadableFile instanceof PhotoModel) {
      return ((PhotoModel) downloadableFile).getDataId();
    }
    else if (downloadableFile instanceof VideoModel) {
      return ((VideoModel) downloadableFile).getDataId();
    }
    return downloadableFile.getIdempotentId();
  }

  private static Long getUploadedTime(DownloadableFile downloadableFile) {
    Date updatedTime = null;
    if (downloadableFile instanceof PhotoModel) {
      updatedTime = ((PhotoModel) downloadableFile).getUploadedTime();
    } else if (downloadableFile instanceof VideoModel) {
      updatedTime = ((VideoModel) downloadableFile).getUploadedTime();
    }
    return updatedTime == null ? null : updatedTime.getTime();
  }

  private static String getDescription(DownloadableFile downloadableFile) {
    if (downloadableFile instanceof PhotoModel) {
      return ((PhotoModel) downloadableFile).getDescription();
    }
    if (downloadableFile instanceof VideoModel) {
      return ((VideoModel) downloadableFile).getDescription();
    }
    return null;
  }

  public static String getApplePhotosImportThrowingMessage(final String cause) {
    return getApplePhotosImportThrowingMessage(cause, ImmutableMap.of());
  }

  public static String getApplePhotosImportThrowingMessage(
      final String cause,
      final ImmutableMap<AuditKeys, Optional<String>> keyValuePairs) {
    String finalLogMessage = String.format("%s " + cause, ApplePhotosConstants.APPLE_PHOTOS_IMPORT_ERROR_PREFIX);
    for (AuditKeys key: keyValuePairs.keySet()){
      finalLogMessage = String.format("%s, %s:%s", finalLogMessage, key.name(), keyValuePairs.get(key).orElse("[n/a]"));
    }
    return finalLogMessage;
  }
}
