package com.acme.hrms.integration;

/**
 * Object-storage port (ARCH-05): replaces the {@code UTL_FILE} directories. Keys follow {@code
 * {feed}/{yyyy}/{MM}/{fileId}-{fileName}} inside bucket {@code hrms-integration}.
 */
public interface ObjectStorage {
  String BUCKET = "hrms-integration";

  void put(String bucket, String key, byte[] content);

  byte[] get(String bucket, String key);
}
