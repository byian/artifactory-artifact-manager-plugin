package io.jenkins.plugins.artifactory_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import jenkins.util.VirtualFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactoryVirtualFile extends ArtifactoryAbstractVirtualFile {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryVirtualFile.class);

    /**
     * ThreadLocal cache for storing metadata to avoid excessive API calls.
     * Pattern follows S3 and Azure artifact manager plugins.
     * Keyed by repository name, values are stacks of cache frames for nested calls.
     */
    private static final ThreadLocal<Map<String, Deque<CacheFrame>>> cache = new ThreadLocal<>();

    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String key;

    private final transient Run<?, ?> build;
    private final ArtifactoryClient.FileInfo fileInfo;

    public ArtifactoryVirtualFile(String key, Run<?, ?> build) {
        this.key = key;
        this.build = build;
        this.fileInfo = null;
    }

    public ArtifactoryVirtualFile(ArtifactoryClient.FileInfo fileInfo, Run<?, ?> build) {
        this.key = fileInfo.getPath();
        this.build = build;
        this.fileInfo = fileInfo;
    }

    public String getKey() {
        return key;
    }

    @NonNull
    @Override
    public String getName() {
        String localKey = Utils.stripTrailingSlash(key);

        localKey = localKey.replaceFirst(".*/artifacts/", "");

        // Return just the filename/foldername, not the full path
        int lastSlash = localKey.lastIndexOf('/');
        String result = lastSlash >= 0 ? localKey.substring(lastSlash + 1) : localKey;

        return result;
    }

    @NonNull
    @Override
    public URI toURI() {
        try {
            return new URI(Utils.getUrl(this.key));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        return new URL(Utils.getUrl(this.key));
    }

    @Override
    public VirtualFile getParent() {
        return new ArtifactoryVirtualFile(this.key.replaceFirst("/[^/]+$", ""), this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.isDirectory();
        }
        String keyWithNoSlash = Utils.stripTrailingSlash(this.key);
        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }
        // Check cache first
        String repository = Utils.getArtifactConfig().getRepository();
        Map<String, Deque<CacheFrame>> m = cache.get();
        if (m != null) {
            Deque<CacheFrame> stack = m.get(repository);
            if (stack != null && !stack.isEmpty()) {
                for (CacheFrame frame : stack) {
                    if (key.startsWith(frame.root)) {
                        String relativePath = key.substring(frame.root.length());
                        // Check if this path is in cache
                        if (frame.files.containsKey(relativePath)) {
                            return false; // It's a file if it's directly in cache
                        }
                        // Check if any cached path starts with this path + "/"
                        String dirPrefix = relativePath.isEmpty() ? "" : relativePath + "/";
                        for (String cachedPath : frame.files.keySet()) {
                            if (cachedPath.startsWith(dirPrefix)) {
                                return true; // It's a directory if we have files under it
                            }
                        }
                    }
                }
            }
        }
        // Fall back to API call
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFolder(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a directory", this.key), e);
            return false;
        }
    }

    @Override
    public boolean isFile() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.isFile();
        }
        String keyS = this.key + "/";
        if (keyS.endsWith("/*view*/")) {
            return false;
        }
        // Check cache first
        CachedMetadata metadata = getCachedMetadata();
        if (metadata != null) {
            return true; // If it's in cache with metadata, it's a file
        }
        // Fall back to API call
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFile(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a file", this.key), e);
            return false;
        }
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() throws IOException {
        String prefix = Utils.stripTrailingSlash(this.key) + "/";

        // Check cache first
        String repository = Utils.getArtifactConfig().getRepository();
        Map<String, Deque<CacheFrame>> m = cache.get();
        if (m != null) {
            Deque<CacheFrame> stack = m.get(repository);
            if (stack != null && !stack.isEmpty()) {
                for (CacheFrame frame : stack) {
                    if (prefix.startsWith(frame.root)) {
                        // List immediate children from cache
                        String relativePrefix = prefix.substring(frame.root.length());
                        Set<String> immediateChildren = new HashSet<>();

                        for (String relativePath : frame.files.keySet()) {
                            if (relativePath.startsWith(relativePrefix)) {
                                String remainder = relativePath.substring(relativePrefix.length());
                                // Get the immediate child name (before next slash)
                                int slashIndex = remainder.indexOf('/');
                                String immediateName = slashIndex == -1 ? remainder : remainder.substring(0, slashIndex);
                                if (!immediateName.isEmpty()) {
                                    immediateChildren.add(immediateName);
                                }
                            }
                        }

                        // Create VirtualFile objects for immediate children
                        return immediateChildren.stream()
                                .map(name -> new ArtifactoryVirtualFile(prefix + name, build))
                                .toArray(VirtualFile[]::new);
                    }
                }
            }
        }

        // Fall back to API call
        List<VirtualFile> files = listFilesFromPrefix(prefix);
        if (files.isEmpty()) {
            return new VirtualFile[0];
        }
        return files.toArray(new VirtualFile[0]);
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String joinedKey = Utils.stripTrailingSlash(this.key) + "/" + name;
        return new ArtifactoryVirtualFile(joinedKey, build);
    }

    @Override
    public long length() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.getSize();
        }
        // Check cache first
        CachedMetadata metadata = getCachedMetadata();
        if (metadata != null) {
            return metadata.length;
        }
        // Fall back to API call
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.size(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get size of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public long lastModified() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.getLastUpdated();
        }
        // Check cache first
        CachedMetadata metadata = getCachedMetadata();
        if (metadata != null) {
            return metadata.lastModified;
        }
        // Fall back to API call
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.lastUpdated(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get last updated time of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.debug(String.format("Opening %s...", this.key));
        if (isDirectory()) {
            throw new FileNotFoundException("Cannot open it because it is a directory.");
        }
        if (!isFile()) {
            throw new FileNotFoundException("Cannot open it because it is not a file.");
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.downloadArtifact(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to open %s", this.key), e);
            throw new IOException(e);
        }
    }

    private ArtifactoryClient buildArtifactoryClient() {
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        return new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
    }

    /**
     * List the immediate children from a prefix
     * @param prefix the prefix (folder path)
     * @return the list of immediate children (files and folders)
     */
    private List<VirtualFile> listFilesFromPrefix(String prefix) {
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            // client.list() now returns only immediate children, not all nested files
            // This eliminates the need for complex path parsing and deduplication
            List<ArtifactoryClient.FileInfo> children = client.list(prefix);

            return children.stream()
                    .map(fileInfo -> new ArtifactoryVirtualFile(fileInfo, this.build))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to list files from prefix %s", prefix), e);
            return Collections.emptyList();
        }
    }

    /**
     * Runs a computation with a cache populated for artifact metadata.
     * Performs a single recursive listing to populate the cache, then serves
     * all subsequent operations from cache to avoid excessive API calls.
     *
     * Pattern follows S3 and Azure artifact manager plugins.
     *
     * @param <V> the return type
     * @param callable the computation to run with cache
     * @return the result of the computation
     * @throws IOException if an error occurs
     */
    public <V> V run(Callable<V> callable) throws IOException {
        String repository = Utils.getArtifactConfig().getRepository();

        Map<String, Deque<CacheFrame>> m = cache.get();
        if (m == null) {
            m = new HashMap<>();
            cache.set(m);
        }

        Deque<CacheFrame> stack = m.computeIfAbsent(repository, k -> new ArrayDeque<>());

        // Populate cache frame with recursive listing
        Map<String, CachedMetadata> files = new HashMap<>();
        String root = Utils.stripTrailingSlash(this.key) + "/";

        try (ArtifactoryClient client = buildArtifactoryClient()) {
            // Recursively list all files under this root to populate cache
            populateCache(client, root, root, files);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to populate cache for %s", root), e);
        }

        CacheFrame frame = new CacheFrame(root, files);
        stack.push(frame);

        try {
            return callable.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                m.remove(repository);
                if (m.isEmpty()) {
                    cache.remove();
                }
            }
        }
    }

    /**
     * Recursively populate cache with all files under the given prefix.
     * All paths are stored relative to the original root, not the current prefix.
     *
     * @param client the Artifactory client
     * @param root the original root path (remains constant across recursive calls)
     * @param currentPrefix the current prefix being listed
     * @param files the map to populate with relative paths to metadata
     */
    private void populateCache(
            ArtifactoryClient client, String root, String currentPrefix, Map<String, CachedMetadata> files)
            throws IOException {
        List<ArtifactoryClient.FileInfo> children = client.list(currentPrefix);

        for (ArtifactoryClient.FileInfo child : children) {
            // Calculate path relative to original root, not current prefix
            String relativePath = child.getPath().substring(root.length());
            files.put(relativePath, new CachedMetadata(child.getSize(), child.getLastUpdated()));

            // Recursively list subdirectories
            if (child.isDirectory()) {
                String childPrefix = child.getPath();
                if (!childPrefix.endsWith("/")) {
                    childPrefix += "/";
                }
                populateCache(client, root, childPrefix, files);
            }
        }
    }

    /**
     * Gets cached metadata for a relative path
     */
    private CachedMetadata getCachedMetadata() {
        String repository = Utils.getArtifactConfig().getRepository();
        Map<String, Deque<CacheFrame>> m = cache.get();
        if (m == null) {
            return null;
        }

        Deque<CacheFrame> stack = m.get(repository);
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        for (CacheFrame frame : stack) {
            if (key.startsWith(frame.root)) {
                String relativePath = key.substring(frame.root.length());
                CachedMetadata metadata = frame.files.get(relativePath);
                if (metadata != null) {
                    return metadata;
                }
            }
        }

        return null;
    }

    /**
     * Cached metadata for a file
     */
    private static final class CachedMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        final long length;
        final long lastModified;

        CachedMetadata(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }

    /**
     * A cache frame representing metadata for files under a root directory
     */
    private static final class CacheFrame implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Root directory path with trailing slash */
        final String root;

        /** Map of relative paths to cached metadata */
        final Map<String, CachedMetadata> files;

        CacheFrame(String root, Map<String, CachedMetadata> files) {
            this.root = root;
            this.files = files;
        }
    }
}
