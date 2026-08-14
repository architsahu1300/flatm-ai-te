package com.flatmaite.common.storage;

import org.springframework.web.multipart.MultipartFile;

/** File storage abstraction — local disk in MVP, S3-shaped for later. */
public interface StorageProvider {

  /** Stores the file and returns its public URL path (e.g. /uploads/listings/abc.jpg). */
  String store(MultipartFile file, String keyPrefix);

  void delete(String urlPath);
}
