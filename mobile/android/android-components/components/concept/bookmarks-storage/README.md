# [android-components](../../../README.md) > Concept > Bookmarks Storage

The `concept-bookmarks-storage` component contains interfaces and abstract classes that describe the bookmarks storage layer.

This abstraction makes it possible to build components that work independently of the storage layer being used.

## Usage

### Setting up the dependency

Use Gradle to download the library from maven.mozilla.org:

```Groovy
implementation "org.mozilla.components:concept-bookmarks-storage:{latest-version}"
```

### BookmarksStorage

Implement `BookmarksStorage` to provide a concrete bookmarks storage backend:

```kotlin
class MyBookmarksStorage : BookmarksStorage {
    override suspend fun getTree(guid: String, recursive: Boolean): Result<BookmarkNode?> { ... }
    override suspend fun getBookmark(guid: String): Result<BookmarkNode?> { ... }
    override suspend fun addItem(parentGuid: String, url: String, title: String, position: UInt?): Result<String> { ... }
    // ... other methods
}
```

Consume a `BookmarksStorage` implementation without depending on its concrete type:

```kotlin
class BookmarkFeature(private val storage: BookmarksStorage) {
    suspend fun getRecentBookmarks() = storage.getRecentBookmarks(limit = 20)
}
```

## License

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/
