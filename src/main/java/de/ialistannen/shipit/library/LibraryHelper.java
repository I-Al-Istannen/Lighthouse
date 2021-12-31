package de.ialistannen.shipit.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

public class LibraryHelper {

  private final Path libraryFolder;

  public LibraryHelper() throws IOException {
    libraryFolder = Files.createTempDirectory("shipit-official-libraries");

    cloneLibrary();
  }

  private void cloneLibrary() throws IOException {
    try {
      Git.cloneRepository()
        .setURI("https://github.com/docker-library/official-images")
        .setDirectory(libraryFolder.toFile())
        .setCloneSubmodules(false)
        .call();
    } catch (GitAPIException e) {
      throw new IOException("Error cloning repo", e);
    }
  }

  public boolean isLibraryImage(String image) {
    return Files.exists(libraryFolder.resolve("library").resolve(image));
  }
}
