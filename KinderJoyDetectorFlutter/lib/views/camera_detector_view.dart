import 'dart:typed_data';
import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/detection.dart';
import '../services/detector_manager.dart';
import '../services/glb_asset_loader.dart';
import '../services/yolo_detector.dart';
import 'ar_model_overlay.dart';
import 'overlay_painter.dart';
import 'result_card_view.dart';

class CameraDetectorView extends StatefulWidget {
  const CameraDetectorView({super.key});

  @override
  State<CameraDetectorView> createState() => _CameraDetectorViewState();
}

class _CameraDetectorViewState extends State<CameraDetectorView> with WidgetsBindingObserver {
  CameraController? _cameraController;
  final YoloDetector _detector = YoloDetector();
  final DetectorManager _manager = DetectorManager();

  bool _isInitializing = true;
  bool _isProcessingFrame = false;
  String _errorMessage = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initApp();
  }

  Future<void> _initApp() async {
    try {
      await _detector.load();
      await GlbAssetLoader.preloadAllModels();

      final cameras = await availableCameras();
      if (cameras.isEmpty) {
        setState(() {
          _errorMessage = 'No camera hardware found on this device.';
          _isInitializing = false;
        });
        return;
      }

      final rearCamera = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );

      _cameraController = CameraController(
        rearCamera,
        ResolutionPreset.medium,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.jpeg,
      );

      await _cameraController!.initialize();
      await _cameraController!.startImageStream(_processCameraFrame);

      setState(() {
        _isInitializing = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Camera initialization failed: $e';
        _isInitializing = false;
      });
    }
  }

  void _processCameraFrame(CameraImage image) async {
    if (_isProcessingFrame || !_detector.isLoaded) return;
    _isProcessingFrame = true;

    try {
      final Size screenSize = MediaQuery.of(context).size;
      final double boxSize = screenSize.width * 0.65;
      final Rect guideBoxOnScreen = Rect.fromCenter(
        center: Offset(screenSize.width / 2, screenSize.height / 2),
        width: boxSize,
        height: boxSize,
      );

      final bool isPortrait = MediaQuery.of(context).orientation == Orientation.portrait;
      final int frameW = isPortrait ? image.height : image.width;
      final int frameH = isPortrait ? image.width : image.height;

      final double scaleX = frameW / screenSize.width;
      final double scaleY = frameH / screenSize.height;

      final Rect frameCropRect = Rect.fromLTRB(
        (guideBoxOnScreen.left * scaleX).clamp(0.0, frameW - 1),
        (guideBoxOnScreen.top * scaleY).clamp(0.0, frameH - 1),
        (guideBoxOnScreen.right * scaleX).clamp(1.0, frameW.toDouble()),
        (guideBoxOnScreen.bottom * scaleY).clamp(1.0, frameH.toDouble()),
      );

      final Uint8List rgbaPixels = _extractRGBABuffer(image);

      final List<Detection> rawFrameDetections = _detector.detect(
        rgbaPixels: rgbaPixels,
        width: image.width,
        height: image.height,
        cropRect: frameCropRect,
      );

      final List<Detection> screenDetections = rawFrameDetections.map((det) {
        final screenRect = Rect.fromLTRB(
          det.rect.left / scaleX,
          det.rect.top / scaleY,
          det.rect.right / scaleX,
          det.rect.bottom / scaleY,
        );
        return det.copyWith(rect: screenRect);
      }).toList();

      _manager.processFrameDetections(screenDetections, guideBoxOnScreen);
    } catch (e) {
      debugPrint('Frame processing error: $e');
    } finally {
      _isProcessingFrame = false;
    }
  }

  Uint8List _extractRGBABuffer(CameraImage image) {
    final int width = image.width;
    final int height = image.height;
    final Uint8List rgba = Uint8List(width * height * 4);

    if (image.format.group == ImageFormatGroup.jpeg && image.planes.isNotEmpty) {
      return image.planes[0].bytes;
    }

    final Uint8List yPlane = image.planes[0].bytes;
    int rgbaIdx = 0;
    for (int i = 0; i < yPlane.length && rgbaIdx + 3 < rgba.length; i++) {
      final int y = yPlane[i];
      rgba[rgbaIdx] = y;
      rgba[rgbaIdx + 1] = y;
      rgba[rgbaIdx + 2] = y;
      rgba[rgbaIdx + 3] = 255;
      rgbaIdx += 4;
    }

    return rgba;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_cameraController == null || !_cameraController!.value.isInitialized) {
      return;
    }
    if (state == AppLifecycleState.inactive) {
      _cameraController?.dispose();
    } else if (state == AppLifecycleState.resumed) {
      _initApp();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _cameraController?.stopImageStream();
    _cameraController?.dispose();
    _detector.dispose();
    _manager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final screenSize = MediaQuery.of(context).size;
    final double boxSize = screenSize.width * 0.65;
    final Rect guideBox = Rect.fromCenter(
      center: Offset(screenSize.width / 2, screenSize.height / 2),
      width: boxSize,
      height: boxSize,
    );

    if (_isInitializing) {
      return Scaffold(
        backgroundColor: const Color(0xFF0F0F1A),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(color: Color(0xFF00E5FF)),
              const SizedBox(height: 20),
              Text(
                'Initializing Kinder Joy AR Detector...',
                style: GoogleFonts.outfit(color: Colors.white, fontSize: 16),
              ),
            ],
          ),
        ),
      );
    }

    if (_errorMessage.isNotEmpty) {
      return Scaffold(
        backgroundColor: const Color(0xFF0F0F1A),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.error_outline, color: Colors.redAccent, size: 60),
                const SizedBox(height: 16),
                Text(
                  _errorMessage,
                  textAlign: TextAlign.center,
                  style: GoogleFonts.outfit(color: Colors.white, fontSize: 16),
                ),
                const SizedBox(height: 24),
                ElevatedButton.icon(
                  onPressed: _initApp,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Retry Camera'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          // 1. Live Camera Preview
          if (_cameraController != null && _cameraController!.value.isInitialized)
            SizedBox.expand(
              child: FittedBox(
                fit: BoxFit.cover,
                child: SizedBox(
                  width: _cameraController!.value.previewSize?.height ?? screenSize.width,
                  height: _cameraController!.value.previewSize?.width ?? screenSize.height,
                  child: CameraPreview(_cameraController!),
                ),
              ),
            ),

          // 2. Custom Painter Overlay (Guide Box, Progress Ring, Bounding Boxes)
          AnimatedBuilder(
            animation: _manager,
            builder: (context, child) {
              return CustomPaint(
                size: screenSize,
                painter: OverlayPainter(
                  guideBox: guideBox,
                  detections: _manager.currentDetections,
                  progress: _manager.progress,
                  state: _manager.state,
                ),
              );
            },
          ),

          // 3. 3D AR Model Overlay (Render during Confirming or Confirmed states)
          AnimatedBuilder(
            animation: _manager,
            builder: (context, child) {
              final activeDet = _manager.confirmedDetection ??
                  (_manager.currentDetections.isNotEmpty ? _manager.currentDetections.first : null);

              if ((_manager.state == DetectionState.confirmed ||
                      _manager.state == DetectionState.confirming) &&
                  activeDet != null) {
                return ArModelOverlay(
                  detection: activeDet,
                  screenSize: screenSize,
                );
              }
              return const SizedBox.shrink();
            },
          ),

          // 4. Status Header
          Positioned(
            top: 50,
            left: 20,
            right: 20,
            child: AnimatedBuilder(
              animation: _manager,
              builder: (context, child) {
                return Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.black.withOpacity(0.75),
                    borderRadius: BorderRadius.circular(30),
                    border: Border.all(color: Colors.white12),
                    boxShadow: const [
                      BoxShadow(
                        color: Colors.black45,
                        blurRadius: 15,
                      )
                    ],
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        _manager.isLocked
                            ? Icons.check_circle_rounded
                            : Icons.center_focus_strong,
                        color: _manager.isLocked
                            ? const Color(0xFF2ECC71)
                            : const Color(0xFF00E5FF),
                        size: 20,
                      ),
                      const SizedBox(width: 10),
                      Flexible(
                        child: Text(
                          _manager.statusMessage,
                          style: GoogleFonts.outfit(
                            color: Colors.white,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),

          // 5. Result Card Popup
          AnimatedBuilder(
            animation: _manager,
            builder: (context, child) {
              if (_manager.state == DetectionState.confirmed &&
                  _manager.confirmedDetection != null) {
                return ResultCardView(
                  detection: _manager.confirmedDetection!,
                  onScanAgain: () => _manager.resetScan(),
                );
              }
              return const SizedBox.shrink();
            },
          ),
        ],
      ),
    );
  }
}
