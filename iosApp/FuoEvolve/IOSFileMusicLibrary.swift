import AVFoundation
import Foundation
import Shared

final class IOSFileMusicLibrary: NSObject, IosFileMusicOutput {
    static let shared = IOSFileMusicLibrary()

    private let supportedExtensions: Set<String> = [
        "mp3", "m4a", "aac", "flac", "wav", "aif", "aiff"
    ]

    func tracksJson() -> String {
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let keys: [URLResourceKey] = [
            .isDirectoryKey,
            .isRegularFileKey,
            .isHiddenKey,
            .isSymbolicLinkKey
        ]
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles],
            errorHandler: { _, _ in true }
        ) else {
            return emptyResult()
        }

        var tracks: [FileTrack] = []
        while let url = enumerator.nextObject() as? URL {
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { continue }
            if values.isSymbolicLink == true {
                if values.isDirectory == true {
                    enumerator.skipDescendants()
                }
                continue
            }
            if values.isHidden == true || url.lastPathComponent.hasPrefix(".") {
                if values.isDirectory == true {
                    enumerator.skipDescendants()
                }
                continue
            }
            guard values.isRegularFile == true,
                  supportedExtensions.contains(url.pathExtension.lowercased()) else {
                continue
            }
            tracks.append(track(for: url, documentsRoot: root))
        }

        do {
            let data = try JSONEncoder().encode(FileTrackRoot(tracks: tracks))
            return String(data: data, encoding: .utf8) ?? emptyResult()
        } catch {
            return emptyResult()
        }
    }

    private func track(for url: URL, documentsRoot: URL) -> FileTrack {
        let asset = AVURLAsset(url: url)
        let fallbackTitle = url.deletingPathExtension().lastPathComponent
        let durationSeconds = CMTimeGetSeconds(asset.duration)
        let durationMilliseconds: Int64
        if durationSeconds.isFinite && durationSeconds >= 0 {
            durationMilliseconds = Int64((durationSeconds * 1_000).rounded())
        } else {
            durationMilliseconds = 0
        }
        return FileTrack(
            relativePath: relativePath(for: url, documentsRoot: documentsRoot),
            uri: url.standardizedFileURL.absoluteString,
            title: metadataValue(.commonKeyTitle, asset: asset) ?? fallbackTitle,
            artists: metadataValue(.commonKeyArtist, asset: asset) ?? "未知歌手",
            album: metadataValue(.commonKeyAlbumName, asset: asset) ?? "",
            durationMilliseconds: durationMilliseconds
        )
    }

    private func metadataValue(_ key: AVMetadataKey, asset: AVAsset) -> String? {
        return asset.commonMetadata
            .first(where: { $0.commonKey == key })?
            .stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty
    }

    private func relativePath(for url: URL, documentsRoot: URL) -> String {
        let rootPath = documentsRoot.standardizedFileURL.path
        let filePath = url.standardizedFileURL.path
        guard filePath.hasPrefix(rootPath) else { return url.lastPathComponent }
        return String(filePath.dropFirst(rootPath.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private func emptyResult() -> String {
        return #"{"tracks":[]}"#
    }

    private struct FileTrackRoot: Encodable {
        let tracks: [FileTrack]
    }

    private struct FileTrack: Encodable {
        let relativePath: String
        let uri: String
        let title: String
        let artists: String
        let album: String
        let durationMilliseconds: Int64

        enum CodingKeys: String, CodingKey {
            case relativePath = "relative_path"
            case uri
            case title
            case artists
            case album
            case durationMilliseconds = "duration_ms"
        }
    }
}

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }
}
