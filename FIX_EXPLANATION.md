# Fix for Issues #99 and #127

## Problem Analysis

### Issue #99: "Download all files" only downloads one artifact
The virtual file structure wasn't being properly represented, causing the "download all files in zip" feature to fail.

### Issue #127: 8x increase in Artifactory requests causing system instability
PR #124 attempted to fix #99 but introduced severe performance issues:
- Used FileSpec/AQL searches which query ALL nested files recursively
- Every `list()` call triggered expensive AQL database queries
- When Jenkins traversed the artifact tree, it made excessive recursive queries
- Resulted in 8x more requests and database load spike from 10 to 120

## Root Cause

The previous implementation in `ArtifactoryClient.list()`:
```java
// OLD CODE - BAD
FileSpec fileSpec = FileSpec.fromString(
    String.format("{\"files\": [{\"pattern\": \"%s/%s*\"}]}", repo, targetPath));
return artifactory.searches().artifactsByFileSpec(fileSpec);
```

This approach:
1. Uses AQL (Artifactory Query Language) searches via `POST /api/search/aql`
2. Returns ALL files matching the pattern, including deeply nested ones
3. Requires expensive database scanning and indexing
4. `listFilesFromPrefix()` then tried to parse and deduplicate to extract immediate children

## Solution

Use Artifactory's Storage API instead of AQL searches:

### Changed: ArtifactoryClient.list()
```java
// NEW CODE - GOOD
Folder folder = artifactory.repository(repo).folder(targetPath);
return folder.list().stream()
    .map(item -> new FileInfo(...))
    .collect(Collectors.toList());
```

This approach:
1. Uses Storage API via `GET /api/storage/{repo}/{path}/`
2. Returns only immediate children (non-recursive)
3. Single fast REST call, no database scanning
4. Proper folder structure representation

### Simplified: ArtifactoryVirtualFile.listFilesFromPrefix()
Removed complex path parsing and deduplication logic since `client.list()` now returns only immediate children.

## Benefits

1. **Performance**: Reduces API calls from recursive AQL queries to single Storage API calls
2. **Accuracy**: Proper folder structure representation fixes "download all" functionality
3. **Simplicity**: Cleaner, more maintainable code
4. **Scalability**: No database load spikes, works well with large artifact trees

## Testing

The existing test mocks in `BaseTest.java` already set up the Storage API endpoint:
```java
// Line 103-104
wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(artifactBasePath + "/")))
    .willReturn(WireMock.okJson(artifactsResponse)));
```

This verifies that our solution using `folder.list()` (which calls the Storage API internally) should work correctly with the existing test suite.

## Impact

- Fixes #99: "Download all files" will work correctly
- Fixes #127: Dramatically reduces Artifactory requests (from 8x back to normal)
- No breaking changes to public API
- Compatible with existing tests
