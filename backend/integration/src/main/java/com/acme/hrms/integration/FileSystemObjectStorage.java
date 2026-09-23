package com.acme.hrms.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local-disk adapter of {@link ObjectStorage} (the S3-compatible adapter is a deployment concern;
 * both honour the same bucket/key convention). Root is {@code hrms.integration.storage.root}.
 */
@Component
public class FileSystemObjectStorage implements ObjectStorage {
  private final Path root;

  public FileSystemObjectStorage(
      @Value("${hrms.integration.storage.root:${java.io.tmpdir}/hrms-object-storage}")
          String root) {
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  @Override
  public void put(String bucket, String key, byte[] content) {
    Path target = resolve(bucket, key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("object storage put failed: " + bucket + "/" + key, e);
    }
  }

  @Override
  public byte[] get(String bucket, String key) {
    try {
      return Files.readAllBytes(resolve(bucket, key));
    } catch (IOException e) {
      throw new UncheckedIOException("object storage get failed: " + bucket + "/" + key, e);
    }
  }

  private Path resolve(String bucket, String key) {
    Path p = root.resolve(bucket).resolve(key).normalize();
    if (!p.startsWith(root)) {
      throw new IllegalArgumentException("key escapes storage root: " + key);
    }
    return p;
  }
}
