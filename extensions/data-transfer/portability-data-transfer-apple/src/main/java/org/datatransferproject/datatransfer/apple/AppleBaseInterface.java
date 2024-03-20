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

package org.datatransferproject.datatransfer.apple;

/**
 * The Base Interface for all the {@link org.datatransferproject.types.common.models.DataVertical}s
 * that Apple Supports.
 */
public interface AppleBaseInterface {
    enum AuditKeys {
        albumId,
        body,
        bytesImported,
        bytesExported,
        context,
        creationTimeInMs,
        dataId,
        dataType,
        detailedFailureReason,
        downloadURL,
        durationInMillis,
        endpoint,
        errorCode,
        errorMessage,
        errorSource,
        exception,
        failureReason,
        failedFilesCount,
        exportService,
        exportDurationInMillis,
        headers,
        idempotentId,
        importService,
        importDurationInMillis,
        itemName,
        jobId,
        jobAuthorizationState,
        jobState,
        method,
        numErrors,
        otherAppId,
        providerId,
        processedFilesCount,
        queueSize,
        recordId,
        response,
        retryCount,
        requestTimeInMillis,
        service,
        success,
        statusCode,
        totalFilesCount,
        transactionId,
        updatedTimeInMs,
        uri,
        value,
        error,
    }

}
