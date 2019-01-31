/*
 * Copyright 2019 The Data Transfer Project Authors.
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

package org.datatransferproject.datatransfer.imgur;

import java.io.IOException;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.datatransferproject.api.launcher.ExtensionContext;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.spi.cloud.storage.AppCredentialStore;
import org.datatransferproject.spi.transfer.extension.TransferExtension;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.datatransfer.imgur.photos.ImgurPhotosExporter;
import org.datatransferproject.datatransfer.imgur.photos.ImgurPhotosImporter;
import org.datatransferproject.types.transfer.auth.AppCredentials;

/** Extension for transferring Imgur data */
public class ImgurTransferExtension implements TransferExtension {
  private static final String SERVICE_ID = "Imgur";
  private static final String BASE_URL = "https://api.imgur.com/3";

  private boolean initialized = false;

  private static final ImmutableList<String> SUPPORTED_DATA_TYPES = ImmutableList.of("PHOTOS");

  private ImgurPhotosImporter importer;
  private ImgurPhotosExporter exporter;

  @Override
  public void initialize(ExtensionContext context) {
    Monitor monitor = context.getMonitor();
    if (initialized) {
      monitor.severe(() -> "ImgurTransferExtension is already initialized");
      return;
    }

    AppCredentials appCredentials;
    try {
      appCredentials =
          context
              .getService(AppCredentialStore.class)
              .getAppCredentials("IMGUR_KEY", "IMGUR_SECRET");
    } catch (IOException e) {
      monitor.info(
          () -> "Unable to retrieve Imgur AppCredentials. Did you set Imgur_KEY and Imgur_SECRET?",
          e);
      return;
    }

    exporter = new ImgurPhotosExporter(monitor, BASE_URL);
    importer = new ImgurPhotosImporter(appCredentials);

    initialized = true;
  }

  @Override
  public String getServiceId() {
    return SERVICE_ID;
  }

  @Override
  public Exporter<?, ?> getExporter(String transferDataType) {
    Preconditions.checkArgument(
        initialized, "ImgurTransferExtension is not initialized. Unable to get Exporter");
    Preconditions.checkArgument(
        SUPPORTED_DATA_TYPES.contains(transferDataType),
        "ImgurTransferExtension doesn't support " + transferDataType);
    return exporter;
  }

  @Override
  public Importer<?, ?> getImporter(String transferDataType) {
    Preconditions.checkArgument(
        initialized, "ImgurTransferExtension is not initialized. Unable to get Importer");
    Preconditions.checkArgument(
        SUPPORTED_DATA_TYPES.contains(transferDataType),
        "ImgurTransferExtension doesn't support " + transferDataType);
    return importer;
  }
}
