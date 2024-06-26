# portability-types-transfer

This folder contains the core DTP-framework interfaces used throughout the DTP's codebase.
For more general, common models see the `org.datatransferproject.types.common.models.*`
packages over in `/portability-types-common/src/main/java/org/datatransferproject/types/common/models/`.

 - [auth](src/main/java/org/datatransferproject/types/transfer/auth) -
   common data structures for passing auth data around the DTP system 
 - [errors](src/main/java/org/datatransferproject/types/transfer/errors) -
   common data structure for errors passed around or serialized
 - [retry](src/main/java/org/datatransferproject/types/transfer/retry) -
   common data structures for encoding the retry behavior for a server when
   there is an error.
