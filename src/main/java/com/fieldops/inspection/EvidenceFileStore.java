package com.fieldops.inspection;

import com.fieldops.shared.error.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Guarda o arquivo da evidência fora do banco. A chave fica em evidence.storage_key. */
@Component
public class EvidenceFileStore {
  private final Path root;

  public EvidenceFileStore(@Value("${fieldops.evidence.directory:}") String directory) {
    root = (directory == null || directory.isBlank()
        ? Path.of(System.getProperty("java.io.tmpdir"), "fieldops-evidence")
        : Path.of(directory)).toAbsolutePath().normalize();
  }

  public void write(String storageKey, byte[] bytes) {
    try {
      Path target = resolve(storageKey);
      Files.createDirectories(target.getParent());
      Files.write(target, bytes);
    } catch (IOException ex) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EVIDENCE_STORE_FAILED", "Evidence file could not be stored");
    }
  }

  public byte[] read(String storageKey) {
    try {
      return Files.readAllBytes(resolve(storageKey));
    } catch (IOException ex) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "Evidence file not found");
    }
  }

  private Path resolve(String storageKey) {
    if (storageKey == null || storageKey.isBlank() || storageKey.contains("..")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_INVALID", "Evidence file is invalid");
    }
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_INVALID", "Evidence file is invalid");
    }
    return target;
  }
}
