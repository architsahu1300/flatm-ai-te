package com.flatmaite.common.storage;

import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.common.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class LocalDiskStorageProvider implements StorageProvider {

  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "svg");

  private final FlatmaiteProperties props;

  @Override
  public String store(MultipartFile file, String keyPrefix) {
    String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
    String ext = original.contains(".")
        ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
        : "";
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      throw ApiException.badRequest("unsupported_file", "Only jpg, png, webp or svg images are allowed");
    }
    String key = keyPrefix + "/" + UUID.randomUUID() + "." + ext;
    try {
      Path target = Paths.get(props.getStorage().getUploadDir()).resolve(key).normalize();
      Files.createDirectories(target.getParent());
      file.transferTo(target.toAbsolutePath().toFile());
      return "/uploads/" + key;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to store file", e);
    }
  }

  @Override
  public void delete(String urlPath) {
    if (urlPath == null || !urlPath.startsWith("/uploads/")) {
      return;
    }
    try {
      Path target =
          Paths.get(props.getStorage().getUploadDir())
              .resolve(urlPath.substring("/uploads/".length()))
              .normalize();
      Files.deleteIfExists(target);
    } catch (Exception ignored) {
      // best effort
    }
  }
}
