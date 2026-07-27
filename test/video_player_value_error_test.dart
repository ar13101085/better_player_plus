import 'package:better_player_plus/src/video_player/video_player.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pins the error-latch semantics of [VideoPlayerValue.copyWith].
///
/// `errorDescription ?? this.errorDescription` means a routine copyWith can
/// never clear an error — deliberate, so position/buffering ticks don't wipe
/// it. But recovery paths (a new data source, an `initialized` event proving
/// the source alive) MUST be able to drop a stale error explicitly, or it
/// re-emits as an `exception` event on every subsequent tick and the app
/// tears a healthy stream down in an endless retry loop.
void main() {
  test('copyWith keeps an existing error by default (the latch)', () {
    final errored = VideoPlayerValue.erroneous('Source error: dead channel');
    expect(errored.hasError, isTrue);

    // Routine event updates flow through copyWith with no errorDescription —
    // the error must survive them.
    final afterTick = errored.copyWith(position: const Duration(seconds: 1));
    expect(afterTick.hasError, isTrue);
    expect(afterTick.errorDescription, 'Source error: dead channel');
  });

  test('clearError drops the error without touching anything else', () {
    final errored = VideoPlayerValue.erroneous('Source error: dead channel')
        .copyWith(position: const Duration(seconds: 3), volume: 0.5);

    final cleared = errored.copyWith(clearError: true);
    expect(cleared.hasError, isFalse);
    expect(cleared.errorDescription, isNull);
    expect(cleared.position, const Duration(seconds: 3));
    expect(cleared.volume, 0.5);
  });

  test('clearError wins even when an errorDescription is also passed', () {
    final v = VideoPlayerValue.erroneous('old').copyWith(
      clearError: true,
      errorDescription: 'new',
    );
    expect(v.hasError, isFalse);
  });
}
