import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

/// Helper service that extracts Flutter asset GLB files to local device storage,
/// ensuring 100% reliable WebGL loading in 3D Model Viewers on Android & iOS.
class GlbAssetLoader {
  static final Map<String, String> _fileCache = {};

  /// Preload and extract all GLB models to local device storage
  static Future<void> preloadAllModels() async {
    const glbAssets = [
      'assets/models/harry_potter.glb',
      'assets/models/hermione.glb',
      'assets/models/batman.glb',
      'assets/models/flash.glb',
    ];

    for (final asset in glbAssets) {
      try {
        await getLocalFilePath(asset);
      } catch (e) {
        debugPrint('Error preloading $asset: $e');
      }
    }
  }

  /// Copies an asset GLB file to local documents directory and returns its absolute path
  static Future<String> getLocalFilePath(String assetPath) async {
    if (_fileCache.containsKey(assetPath)) {
      final cachedPath = _fileCache[assetPath]!;
      if (await File(cachedPath).exists()) {
        return cachedPath;
      }
    }

    try {
      final docDir = await getApplicationDocumentsDirectory();
      final fileName = assetPath.split('/').last;
      final localFile = File('${docDir.path}/$fileName');

      // Write asset bytes to local storage if not present or size mismatch
      final ByteData byteData = await rootBundle.load(assetPath);
      final Uint8List bytes = byteData.buffer.asUint8List(
        byteData.offsetInBytes,
        byteData.lengthInBytes,
      );

      if (!await localFile.exists() || (await localFile.length()) != bytes.length) {
        await localFile.writeAsBytes(bytes, flush: true);
      }

      _fileCache[assetPath] = localFile.path;
      return localFile.path;
    } catch (e) {
      debugPrint('GlbAssetLoader error for $assetPath: $e');
      return assetPath;
    }
  }
}
